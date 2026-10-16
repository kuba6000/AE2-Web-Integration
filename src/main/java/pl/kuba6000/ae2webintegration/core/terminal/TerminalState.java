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
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;

/** Detached live state shared by viewers. Its monitor never covers encoding, native reads or socket writes. */
public final class TerminalState {

    private static final int MAX_SUBSCRIPTIONS = 64;
    private static final int MAX_PER_PRINCIPAL = 4;
    private static final int MAX_PENDING_KEYS = 16_384;
    private static final long RESERVATION_MILLIS = 10_000;
    private boolean closed;
    private final Map<StableKey, Grid> grids = new HashMap<>();
    private final Map<String, Subscription> subscriptions = new HashMap<>();

    @Desugar
    public record View(@NotNull String generation, @NotNull String revision) {}

    @Desugar
    public record Bootstrap(@NotNull String subscription, @NotNull View view,
        @NotNull List<StorageObservation.Row> rows) {}

    @Desugar
    public record Amount(@NotNull StableKey itemKey, long quantity, boolean craftable) {}

    @Desugar
    public record Update(@NotNull View view, @NotNull List<Amount> updates,
        @NotNull List<StorageObservation.Row> definitions, @NotNull List<StableKey> removed,
        @Nullable List<StorageObservation.Row> unkeyed) {}

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
        grids.put(key, new Grid(generation, now + RESERVATION_MILLIS));
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
        List<StorageObservation.Row> unkeyed = new ArrayList<>();
        for (StorageObservation.Row row : observation.rows()) {
            StableKey item = row.resource()
                .itemKey();
            boolean present = row.resource()
                .quantity() != 0 || row.craftable();
            if (item == null) {
                if (present) unkeyed.add(row);
                continue;
            }
            if (remaining != null) remaining.remove(item);
            StorageObservation.Row previous = present ? grid.rows.put(item, row) : grid.rows.remove(item);
            if (present ? !row.equals(previous) : previous != null) changed.add(item);
            if (present && (previous == null || !sameDescription(previous, row))) definitions.add(item);
        }
        if (remaining != null) for (StableKey item : remaining) {
            grid.rows.remove(item);
            changed.add(item);
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
            subscriber.unknownChanged |= unknownChanged;
            if (subscriber.pending.size() > MAX_PENDING_KEYS) end(subscriber, "reset");
        }
    }

    /** Snapshot and subscription registration share the same lock; no change can fall between them. */
    public synchronized @Nullable Bootstrap reserve(@NotNull StableKey key, @NotNull WebPrincipal principal,
        boolean icons, @Nullable String packId, long now) {
        expire(now);
        Grid grid = grids.get(key);
        if (closed || grid == null || !grid.initialized || subscriptions.size() >= MAX_SUBSCRIPTIONS) return null;
        long count = subscriptions.values()
            .stream()
            .filter(s -> s.principal.equals(principal))
            .count();
        if (count >= MAX_PER_PRINCIPAL) return null;
        String token = UUID.randomUUID()
            .toString();
        subscriptions
            .put(token, new Subscription(token, key, grid, principal, icons, packId, now + RESERVATION_MILLIS));
        return new Bootstrap(token, view(grid), rows(grid));
    }

    /** Tokens are single-use and bound to the authenticated account, grid and requested representation. */
    synchronized @Nullable Subscription attach(String token, StableKey key, WebPrincipal principal, boolean icons,
        long now) {
        expire(now);
        Subscription subscriber = subscriptions.get(token);
        if (subscriber == null || subscriber.attached
            || subscriber.end != null
            || !subscriber.key.equals(key)
            || !subscriber.principal.equals(principal)
            || subscriber.icons != icons) return null;
        subscriber.attached = true;
        return subscriber;
    }

    synchronized boolean hasPending(Subscription subscriber) {
        return !subscriber.pending.isEmpty() || subscriber.unknownChanged;
    }

    /** Frozen detached rows leave the lock; changes during encoding/writing create a new pending batch. */
    synchronized @Nullable Update drain(Subscription subscriber) {
        if (subscriber.end != null || !hasPending(subscriber)) return null;
        List<StorageObservation.Row> definitions = new ArrayList<>();
        List<Amount> updates = new ArrayList<>();
        List<StableKey> removed = new ArrayList<>();
        for (StableKey key : subscriber.pending) {
            StorageObservation.Row row = subscriber.grid.rows.get(key);
            if (row == null) removed.add(key);
            else {
                updates.add(
                    new Amount(
                        key,
                        row.resource()
                            .quantity(),
                        row.craftable()));
                if (subscriber.definitions.contains(key)) definitions.add(row);
            }
        }
        List<StorageObservation.Row> unknown = subscriber.unknownChanged ? subscriber.grid.unkeyed : null;
        subscriber.pending.clear();
        subscriber.definitions.clear();
        subscriber.unknownChanged = false;
        return new Update(view(subscriber.grid), updates, definitions, removed, unknown);
    }

    synchronized void release(Subscription subscriber) {
        subscriptions.remove(subscriber.token, subscriber);
        subscriber.pending.clear();
        subscriber.definitions.clear();
    }

    public synchronized void cancel(@NotNull String token) {
        Subscription subscriber = subscriptions.get(token);
        if (subscriber != null && !subscriber.attached) release(subscriber);
    }

    public synchronized @Nullable StorageSnapshot snapshot(@NotNull StableKey key) {
        Grid grid = grids.get(key);
        if (grid == null || !grid.initialized) return null;
        return new StorageSnapshot(grid.generation, grid.revision, grid.observedAt, true, true, true, rows(grid));
    }

    /** Full-list invalidation is distinct from maintenance reconciliation and from history coverage loss. */
    public synchronized void invalidate(@NotNull StableKey key, long generation) {
        Grid grid = grids.get(key);
        if (grid == null || grid.generation != generation) return;
        for (Subscription subscriber : subscriptions.values())
            if (subscriber.grid == grid) end(subscriber, "invalidate");
        grid.rows.clear();
        grid.unkeyed = Collections.emptyList();
        grid.initialized = false;
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
        subscriptions.values()
            .removeIf(s -> !s.attached && (s.expires <= now || s.end != null));
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

    synchronized List<Subscription> subscribers() {
        return new ArrayList<>(subscriptions.values());
    }

    synchronized void close() {
        closed = true;
        for (Subscription subscriber : subscriptions.values()) end(subscriber, "invalidate");
        grids.clear();
        subscriptions.clear();
    }

    private static void end(Subscription subscriber, String reason) {
        subscriber.end = reason;
        subscriber.pending.clear();
        subscriber.definitions.clear();
        subscriber.unknownChanged = false;
    }

    private static boolean sameDescription(StorageObservation.Row previous, StorageObservation.Row next) {
        var a = previous.resource();
        var b = next.resource();
        return Objects.equals(a.registryNamespace(), b.registryNamespace())
            && Objects.equals(a.registryPath(), b.registryPath())
            && a.componentCount() == b.componentCount()
            && a.damage() == b.damage()
            && a.displayName()
                .equals(b.displayName())
            && a.resourceType() == b.resourceType()
            && Objects.equals(a.iconBaseKey(), b.iconBaseKey());
    }

    private static View view(Grid grid) {
        return new View(grid.generation + "." + grid.epoch, Long.toString(grid.revision));
    }

    private static List<StorageObservation.Row> rows(Grid grid) {
        List<StorageObservation.Row> rows = new ArrayList<>(grid.rows.values());
        rows.addAll(grid.unkeyed);
        return rows;
    }

    private static final class Grid {

        private final long generation;
        private final Map<StableKey, StorageObservation.Row> rows = new HashMap<>();
        private List<StorageObservation.Row> unkeyed = Collections.emptyList();
        private long epoch;
        private long revision;
        private long observedAt;
        private long activeUntil;
        private boolean initialized;

        private Grid(long generation, long activeUntil) {
            this.generation = generation;
            this.activeUntil = activeUntil;
        }
    }

    static final class Subscription {

        final String token;
        final StableKey key;
        final WebPrincipal principal;
        final boolean icons;
        final @Nullable String packId;
        final long expires;
        final Grid grid;
        final Set<StableKey> pending = new HashSet<>();
        final Set<StableKey> definitions = new HashSet<>();
        boolean unknownChanged;
        boolean attached;
        volatile @Nullable String end;

        private Subscription(String token, StableKey key, Grid grid, WebPrincipal principal, boolean icons,
            @Nullable String packId, long expires) {
            this.token = token;
            this.key = key;
            this.grid = grid;
            this.principal = principal;
            this.icons = icons;
            this.packId = packId;
            this.expires = expires;
        }
    }
}
