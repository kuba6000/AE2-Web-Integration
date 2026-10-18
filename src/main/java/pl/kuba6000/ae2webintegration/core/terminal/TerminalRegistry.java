package pl.kuba6000.ae2webintegration.core.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;

/** Detached live state shared by viewers. Its monitor never covers encoding, native reads or socket writes. */
public final class TerminalRegistry {

    private static final int MAX_SUBSCRIPTIONS = 64;
    private static final int MAX_PER_VIEWER = 6;
    private static final int MAX_PENDING_KEYS = 16_384;
    private static final long RESERVATION_MILLIS = 10_000;
    private static final long STREAM_LEASE_MILLIS = 90_000;
    private boolean closed;
    private long nextRecentToken;
    private final Map<StableKey, Grid> grids = new HashMap<>();
    private final Map<String, Subscription> subscriptions = new HashMap<>();

    @Desugar
    public record View(@NotNull String generation, @NotNull String revision) {}

    @Desugar
    public record Bootstrap(@NotNull String subscription, @NotNull View view,
        @NotNull List<StorageObservation.Row> rows, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<StorageObservation.UnkeyedRow> unkeyed, @Nullable RecentResourceActivity.Summary recent) {}

    @Desugar
    public record Amount(@NotNull StableKey itemKey, long quantity, boolean craftable) {}

    @Desugar
    public record Update(@NotNull View view, @NotNull List<Amount> updates,
        @NotNull List<StorageObservation.Row> definitions, @NotNull List<StableKey> removed,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, @Nullable List<StorageObservation.UnkeyedRow> unkeyed,
        @Nullable RecentResourceActivity.Summary recent) {}

    /** Returns true when a new baseline is needed. Called only while acquiring/renewing a viewer. */
    public synchronized boolean activate(@NotNull StableKey key, long generation, long now) {
        if (closed) return false;
        expire(now);
        Grid current = grids.get(key);
        if (current != null && current.generation == generation) {
            current.activeUntil = now + RESERVATION_MILLIS;
            return !current.initialized;
        }
        if (current != null && current.generation > generation) return false;
        if (current != null) retire(key, current.generation);
        if (grids.size() >= MAX_SUBSCRIPTIONS) return false;
        Grid created = new Grid(generation, now + RESERVATION_MILLIS);
        created.recentToken = ++nextRecentToken;
        grids.put(key, created);
        return true;
    }

    /** Applies only observed keys except for a full reconciliation, which must also find removed rows. */
    public synchronized void observed(@NotNull StableKey key, long generation,
        @NotNull StorageObservation observation) {
        Grid grid = grids.get(key);
        if (grid == null || grid.generation != generation || !observation.stockObserved()) return;
        if (!grid.initialized && !observation.full()) return;
        Set<StableKey> changed = new HashSet<>();
        Set<StableKey> definitions = new HashSet<>();
        Set<StableKey> remaining = observation.full() ? new HashSet<>(grid.rows.keySet()) : null;
        for (StorageObservation.Row row : observation.rows()) {
            StableKey item = row.itemKey();
            boolean present = row.quantity() != 0 || row.craftable();
            if (remaining != null) remaining.remove(item);
            StorageObservation.Row previous = present ? grid.rows.put(item, row) : grid.rows.remove(item);
            ResourceDescriptor description = observation.descriptions()
                .get(item);
            ResourceDescriptor old = present ? grid.descriptions.put(item, description)
                : grid.descriptions.remove(item);
            if (present ? !row.equals(previous) || !description.equals(old) : previous != null) changed.add(item);
            if (present && !description.equals(old)) definitions.add(item);
        }
        if (remaining != null) for (StableKey item : remaining) {
            grid.rows.remove(item);
            grid.descriptions.remove(item);
            changed.add(item);
        }
        List<StorageObservation.UnkeyedRow> unkeyed = new ArrayList<>();
        for (StorageObservation.UnkeyedRow row : observation.unkeyed()) {
            if (row.resource()
                .quantity() != 0 || row.craftable()) unkeyed.add(row);
        }
        boolean unknownChanged = !unkeyed.equals(grid.unkeyed);
        grid.unkeyed = Collections.unmodifiableList(unkeyed);
        grid.observedAt = observation.observedAt();
        boolean updated = !grid.initialized || !changed.isEmpty() || unknownChanged;
        grid.initialized = true;
        if (!updated) return;
        grid.revision++;
        for (Subscription subscriber : subscriptions.values()) {
            if (subscriber.grid != grid || subscriber.end != null) continue;
            subscriber.pending.addAll(changed);
            subscriber.definitions.addAll(definitions);
            if (grid.recent != null) for (StableKey item : definitions) {
                if (grid.recent.resources()
                    .containsKey(item)) {
                    subscriber.recentKeys.add(item);
                    subscriber.recentChanged = true;
                }
            }
            subscriber.unknownChanged |= unknownChanged;
            if (subscriber.pending.size() > MAX_PENDING_KEYS) end(subscriber, "reset");
        }
    }

    /** Snapshot and subscription registration share the same lock; no change can fall between them. */
    public synchronized @Nullable Bootstrap reserve(@NotNull StableKey key, @Nullable AuthSession session,
        boolean icons, @Nullable String packId, @Nullable String replace, long now) {
        expire(now);
        if (session == null || !session.isValid(now)) return null;
        Grid grid = grids.get(key);
        if (closed || grid == null || !grid.initialized) return null;
        Subscription previous = subscriptions.get(replace);
        if (previous != null && previous.session == session && previous.key.equals(key)) {
            end(previous, "reset");
            release(previous);
        }
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS) return null;
        boolean publicMode = Config.INSTANCE.general.publicMode;
        long count = subscriptions.values()
            .stream()
            .filter(s -> publicMode ? s.principal.equals(session.principal()) : s.session == session)
            .count();
        if (count >= MAX_PER_VIEWER) return null;
        String token = UUID.randomUUID()
            .toString();
        subscriptions.put(token, new Subscription(token, key, grid, session, icons, packId, now + RESERVATION_MILLIS));
        return new Bootstrap(
            token,
            view(grid),
            rows(grid),
            new HashMap<>(grid.descriptions),
            grid.unkeyed,
            grid.recent);
    }

    /** Tokens are single-use and bound to the authenticated login session, grid and requested representation. */
    synchronized @Nullable Subscription attach(String token, StableKey key, @Nullable AuthSession session,
        boolean icons, long now) {
        expire(now);
        Subscription subscriber = subscriptions.get(token);
        if (subscriber == null || subscriber.attached
            || subscriber.end != null
            || !subscriber.key.equals(key)
            || subscriber.session != session
            || subscriber.icons != icons) return null;
        subscriber.attached = true;
        subscriber.expires = now + STREAM_LEASE_MILLIS;
        return subscriber;
    }

    synchronized boolean hasPending(Subscription subscriber) {
        return !subscriber.pending.isEmpty() || subscriber.unknownChanged || subscriber.recentChanged;
    }

    /** A client acknowledges its live stream independently of successful server-side socket writes. */
    public synchronized boolean renew(@NotNull StableKey key, @Nullable AuthSession session, @NotNull String token,
        long now) {
        expire(now);
        Subscription subscriber = subscriptions.get(token);
        if (closed || subscriber == null
            || !subscriber.attached
            || subscriber.end != null
            || subscriber.session != session
            || !subscriber.key.equals(key)) return false;
        subscriber.expires = now + STREAM_LEASE_MILLIS;
        return true;
    }

    /** Frozen detached rows leave the lock; changes during encoding/writing create a new pending batch. */
    synchronized @Nullable Update drain(Subscription subscriber) {
        if (subscriber.end != null || !hasPending(subscriber)) return null;
        List<StorageObservation.Row> definitions = new ArrayList<>();
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        List<Amount> updates = new ArrayList<>();
        List<StableKey> removed = new ArrayList<>();
        for (StableKey key : subscriber.pending) {
            StorageObservation.Row row = subscriber.grid.rows.get(key);
            if (row == null) removed.add(key);
            else {
                updates.add(new Amount(key, row.quantity(), row.craftable()));
                if (subscriber.definitions.contains(key)) {
                    definitions.add(row);
                    descriptions.put(key, subscriber.grid.descriptions.get(key));
                }
            }
        }
        List<StorageObservation.UnkeyedRow> unknown = subscriber.unknownChanged ? subscriber.grid.unkeyed : null;
        RecentResourceActivity.Summary recent = null;
        if (subscriber.recentChanged && subscriber.grid.recent != null) {
            Map<StableKey, RecentResourceActivity.Amount> amounts = new HashMap<>();
            for (StableKey key : subscriber.recentKeys) {
                RecentResourceActivity.Amount amount = subscriber.grid.recent.resources()
                    .get(key);
                if (amount != null) amounts.put(key, amount);
            }
            recent = subscriber.grid.recent.withResources(amounts);
        }
        subscriber.pending.clear();
        subscriber.definitions.clear();
        subscriber.unknownChanged = false;
        subscriber.recentChanged = false;
        subscriber.recentKeys.clear();
        return new Update(view(subscriber.grid), updates, definitions, removed, descriptions, unknown, recent);
    }

    synchronized void release(Subscription subscriber) {
        subscriptions.remove(subscriber.token, subscriber);
        subscriber.pending.clear();
        subscriber.definitions.clear();
        subscriber.recentKeys.clear();
    }

    public synchronized void cancel(@NotNull String token) {
        Subscription subscriber = subscriptions.get(token);
        if (subscriber != null && !subscriber.attached) release(subscriber);
    }

    /** Detached current membership for pin admission, without copying the entire inventory or reading native state. */
    public synchronized @Nullable ResourceDescriptor description(@NotNull StableKey key, @NotNull StableKey item) {
        expire(System.currentTimeMillis());
        Grid grid = grids.get(key);
        return closed || grid == null || !grid.initialized ? null : grid.descriptions.get(item);
    }

    public synchronized @Nullable StorageSnapshot snapshot(@NotNull StableKey key) {
        Grid grid = grids.get(key);
        if (grid == null || !grid.initialized) return null;
        return new StorageSnapshot(
            grid.generation,
            grid.revision,
            grid.observedAt,
            true,
            true,
            true,
            rows(grid),
            grid.descriptions,
            grid.unkeyed);
    }

    /** A token identifies this terminal lifetime and its current cache coverage, not only the native generation. */
    public synchronized long recentToken(@NotNull StableKey key, long generation) {
        Grid grid = grids.get(key);
        return grid == null || grid.generation != generation ? 0 : grid.recentToken;
    }

    /** Reject late cache publication without waiting for a queue slot or rebuilding a summary. */
    public synchronized void invalidateRecent(@NotNull StableKey key, long generation) {
        Grid grid = grids.get(key);
        if (grid == null || grid.generation != generation) return;
        grid.recentToken = ++nextRecentToken;
        grid.recent = null;
    }

    /** Published alongside stock; detached immutable summaries never require a history query. */
    public synchronized void recent(@NotNull StableKey key, long generation, long publicationToken,
        @NotNull RecentResourceActivity.Summary summary) {
        Grid grid = grids.get(key);
        if (grid == null || grid.generation != generation || grid.recentToken != publicationToken) return;
        summary = visibleRecent(grid, summary);
        RecentResourceActivity.Summary previous = grid.recent;
        grid.recent = summary;
        Set<StableKey> changed = new HashSet<>();
        for (Map.Entry<StableKey, RecentResourceActivity.Amount> entry : summary.resources()
            .entrySet()) {
            if (previous == null || !entry.getValue()
                .equals(
                    previous.resources()
                        .get(entry.getKey())))
                changed.add(entry.getKey());
        }
        if (changed.isEmpty() && !coverageChanged(previous, summary)) return;
        grid.revision++;
        for (Subscription subscriber : subscriptions.values()) {
            if (subscriber.grid != grid || subscriber.end != null) continue;
            subscriber.recentChanged = true;
            subscriber.recentKeys.addAll(changed);
            if (subscriber.recentKeys.size() > MAX_PENDING_KEYS) end(subscriber, "reset");
        }
    }

    private static RecentResourceActivity.Summary visibleRecent(Grid grid, RecentResourceActivity.Summary summary) {
        Map<StableKey, RecentResourceActivity.Amount> visible = new HashMap<>();
        String flowZero = summary.flowSince() == null || !summary.flowComplete() ? null : "0";
        String stockZero = summary.stockSince() == null
            || Objects.equals(summary.stockSince(), summary.stockObservedAt())
            || !summary.stockComplete() ? null : "0";
        RecentResourceActivity.Amount zero = new RecentResourceActivity.Amount(flowZero, flowZero, stockZero);
        for (StableKey item : grid.rows.keySet()) visible.put(
            item,
            summary.resources()
                .getOrDefault(item, zero));
        return summary.withResources(visible);
    }

    private static boolean coverageChanged(@Nullable RecentResourceActivity.Summary previous,
        RecentResourceActivity.Summary current) {
        return previous == null || previous.flowComplete() != current.flowComplete()
            || previous.stockComplete() != current.stockComplete()
            || !Objects.equals(previous.flowSince(), current.flowSince())
            || !Objects.equals(previous.stockSince(), current.stockSince())
            || !Objects.equals(previous.flowObservedAt(), current.flowObservedAt())
            || !Objects.equals(previous.stockObservedAt(), current.stockObservedAt());
    }

    public synchronized @Nullable RecentResourceActivity.Summary recent(@NotNull StableKey key) {
        Grid grid = grids.get(key);
        return grid == null ? null : grid.recent;
    }

    /** Full-list invalidation is distinct from maintenance reconciliation and from history coverage loss. */
    public synchronized void invalidate(@NotNull StableKey key, long generation) {
        Grid grid = grids.get(key);
        if (grid == null || grid.generation != generation) return;
        for (Subscription subscriber : subscriptions.values())
            if (subscriber.grid == grid) end(subscriber, "invalidate");
        grid.rows.clear();
        grid.descriptions.clear();
        grid.unkeyed = Collections.emptyList();
        grid.initialized = false;
        grid.recent = null;
        grid.recentToken = ++nextRecentToken;
        grid.epoch++;
        grid.revision = 0;
    }

    public synchronized void retire(@NotNull StableKey key, long generation) {
        Grid grid = grids.get(key);
        if (grid == null || grid.generation != generation) return;
        invalidate(key, generation);
        grids.remove(key);
    }

    synchronized void expire(long now) {
        for (Subscription subscriber : subscriptions.values()) {
            if (!subscriber.session.isValid(now)) end(subscriber, "revoked");
            else if (subscriber.attached && subscriber.expires <= now) end(subscriber, "reset");
        }
        subscriptions.values()
            .removeIf(s -> s.end != null || !s.attached && s.expires <= now);
        Iterator<Grid> iterator = grids.values()
            .iterator();
        while (iterator.hasNext()) {
            Grid grid = iterator.next();
            if (grid.activeUntil > now) continue;
            boolean used = subscriptions.values()
                .stream()
                .anyMatch(s -> s.grid == grid && s.end == null);
            if (!used) iterator.remove();
        }
    }

    /** Active reservations and streams keep observation alive without transport driving native reads. */
    public synchronized Set<StableKey> viewedGrids(long now) {
        expire(now);
        Set<StableKey> keys = new HashSet<>();
        for (Subscription subscriber : subscriptions.values()) if (subscriber.end == null) keys.add(subscriber.key);
        return keys;
    }

    synchronized void reset() {
        for (Subscription subscriber : subscriptions.values()) end(subscriber, "invalidate");
        grids.clear();
        subscriptions.clear();
    }

    synchronized void close() {
        closed = true;
        reset();
    }

    private static void end(Subscription subscriber, String reason) {
        subscriber.end = reason;
        subscriber.pending.clear();
        subscriber.definitions.clear();
        subscriber.unknownChanged = false;
        subscriber.recentChanged = false;
        subscriber.recentKeys.clear();
    }

    private static View view(Grid grid) {
        return new View(grid.generation + "." + grid.epoch, Long.toString(grid.revision));
    }

    private static List<StorageObservation.Row> rows(Grid grid) {
        List<StorageObservation.Row> rows = new ArrayList<>(grid.rows.values());
        return rows;
    }

    private static final class Grid {

        private final long generation;
        private final Map<StableKey, StorageObservation.Row> rows = new HashMap<>();
        private final Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        private List<StorageObservation.UnkeyedRow> unkeyed = Collections.emptyList();
        private long epoch;
        private long revision;
        private long observedAt;
        private long activeUntil;
        private boolean initialized;
        private @Nullable RecentResourceActivity.Summary recent;
        private long recentToken;

        private Grid(long generation, long activeUntil) {
            this.generation = generation;
            this.activeUntil = activeUntil;
        }
    }

    static final class Subscription {

        final String token;
        final StableKey key;
        final WebPrincipal principal;
        final AuthSession session;
        final boolean icons;
        final @Nullable String packId;
        long expires;
        final Grid grid;
        final Set<StableKey> pending = new HashSet<>();
        final Set<StableKey> definitions = new HashSet<>();
        final Set<StableKey> recentKeys = new HashSet<>();
        boolean recentChanged;
        boolean unknownChanged;
        boolean attached;
        volatile @Nullable String end;

        private Subscription(String token, StableKey key, Grid grid, AuthSession session, boolean icons,
            @Nullable String packId, long expires) {
            this.token = token;
            this.key = key;
            this.grid = grid;
            this.principal = session.principal();
            this.session = session;
            this.icons = icons;
            this.packId = packId;
            this.expires = expires;
        }
    }
}
