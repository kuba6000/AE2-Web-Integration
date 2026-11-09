package pl.kuba6000.ae2webintegration.core.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.dto.StoredResource;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.live.LiveSubscription;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;
import pl.kuba6000.ae2webintegration.core.live.LiveUpdate;
import pl.kuba6000.ae2webintegration.core.live.LiveView;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;

/** The stored and craftable resources of one grid, with its recent-activity summary. */
public final class StorageTopic extends LiveTopic {

    private final Map<StableKey, StorageObservation.Row> rows = new HashMap<>();
    private final Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
    private final LongSupplier recentTokens;
    private List<StorageObservation.UnkeyedRow> unkeyed = Collections.emptyList();
    private long observedAt;
    private @Nullable RecentResourceActivity.Summary recent;
    private long recentToken;

    /**
     * Current stored quantity and craftability for one resource.
     *
     * @param itemKey   resource key matching a snapshot row or a definition in this event
     * @param quantity  absolute stored quantity in resource units
     * @param craftable whether the resource can currently be crafted
     * @example itemKey AAAAAAAAAAAAAAAAAAAAAA
     * @example quantity 64
     * @example craftable true
     */
    @Desugar
    private record Amount(@NotNull StableKey itemKey, long quantity, boolean craftable) {}

    /**
     * Changes to a resource snapshot. Empty arrays contain no changes; nullable fields have separate rules below.
     *
     * @param view        snapshot generation and decimal revision; revisions may skip numbers
     * @param updates     absolute quantities and craftability for changed keyed resources
     * @param definitions full rows for new or changed resources, replacing rows with the same itemKey
     * @param removed     resource keys to remove from both the resource list and recent tracking measurements
     * @param unkeyed     complete replacement of rows without keys; null keeps the previous list, empty clears it
     * @param icons       atlas metadata for this event's icon references; null adds none. Resolve icon.page against
     *                    these pages, not the snapshot's pages. A changed packId requires a new snapshot
     * @param recent      recent tracking update; null keeps the previous summary. Replace summary metadata and merge
     *                    resources by key, retaining omitted entries. Null measurements mean unavailable, not zero
     * @example unkeyed null
     * @example icons null
     * @example recent null
     */
    @Desugar
    private record Delta(@NotNull LiveView view, @NotNull List<Amount> updates,
        @NotNull List<StoredResource> definitions, @NotNull List<StableKey> removed,
        @Nullable List<StoredResource> unkeyed, @Nullable IconMappings icons,
        @Nullable RecentResourceActivity.Summary recent) implements IResponseBody {}

    @Desugar
    private record Update(@NotNull LiveView view, @NotNull List<Amount> updates,
        @NotNull List<StorageObservation.Row> definitions, @NotNull List<StableKey> removed,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, @Nullable List<StorageObservation.UnkeyedRow> unkeyed,
        @Nullable RecentResourceActivity.Summary recent) implements LiveUpdate {

        @Override
        public boolean describes() {
            return !definitions.isEmpty() || unkeyed != null && !unkeyed.isEmpty();
        }

        @Override
        public @NotNull Delta body(@Nullable IconMappings mappings) {
            return new Delta(
                view,
                updates,
                resources(definitions, descriptions, Collections.emptyList(), mappings),
                removed,
                unkeyed == null ? null : resources(Collections.emptyList(), Collections.emptyMap(), unkeyed, mappings),
                mappings,
                recent);
        }
    }

    StorageTopic(@NotNull StableKey grid, long generation, @NotNull LongSupplier recentTokens) {
        super(keyOf(grid), generation);
        this.recentTokens = recentTokens;
        recentToken = recentTokens.getAsLong();
    }

    public static @NotNull Key keyOf(@NotNull StableKey grid) {
        return new Key(StorageTopic.class, grid, null);
    }

    public static @NotNull List<StoredResource> resources(@NotNull List<StorageObservation.Row> rows,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, @NotNull List<StorageObservation.UnkeyedRow> unkeyed,
        @Nullable IconMappings mappings) {
        List<StoredResource> result = new ArrayList<>(rows.size() + unkeyed.size());
        for (StorageObservation.Row row : rows) {
            result.add(new StoredResource(descriptions.get(row.itemKey()), row.quantity(), row.craftable(), mappings));
        }
        for (StorageObservation.UnkeyedRow row : unkeyed) {
            result.add(
                new StoredResource(
                    row.resource()
                        .description(),
                    row.resource()
                        .quantity(),
                    row.craftable(),
                    mappings));
        }
        return result;
    }

    /** Applies only observed keys except for a full reconciliation, which must also find removed rows. */
    void observed(@NotNull StorageObservation observation) {
        if (!observation.stockObserved()) return;
        if (!initialized() && !observation.full()) return;
        Set<StableKey> changed = new HashSet<>();
        Set<StableKey> definitions = new HashSet<>();
        Set<StableKey> remaining = observation.full() ? new HashSet<>(rows.keySet()) : null;
        for (StorageObservation.Row row : observation.rows()) {
            StableKey item = row.itemKey();
            boolean present = row.quantity() != 0 || row.craftable();
            if (remaining != null) remaining.remove(item);
            StorageObservation.Row previous = present ? rows.put(item, row) : rows.remove(item);
            ResourceDescriptor description = observation.descriptions()
                .get(item);
            ResourceDescriptor old = present ? descriptions.put(item, description) : descriptions.remove(item);
            if (present ? !row.equals(previous) || !description.equals(old) : previous != null) changed.add(item);
            if (present && !description.equals(old)) definitions.add(item);
        }
        if (remaining != null) for (StableKey item : remaining) {
            rows.remove(item);
            descriptions.remove(item);
            changed.add(item);
        }
        List<StorageObservation.UnkeyedRow> visibleUnkeyed = new ArrayList<>();
        for (StorageObservation.UnkeyedRow row : observation.unkeyed()) {
            if (row.resource()
                .quantity() != 0 || row.craftable()) visibleUnkeyed.add(row);
        }
        boolean unkeyedChanged = !visibleUnkeyed.equals(unkeyed);
        unkeyed = Collections.unmodifiableList(visibleUnkeyed);
        observedAt = observation.observedAt();
        boolean updated = !initialized() || !changed.isEmpty() || unkeyedChanged;
        markInitialized();
        if (!updated) return;
        Set<StableKey> summaryKeys = new HashSet<>();
        if (recent != null) for (StableKey item : definitions) {
            if (recent.resources()
                .containsKey(item)) summaryKeys.add(item);
        }
        publish(changed, definitions, unkeyedChanged, summaryKeys, !summaryKeys.isEmpty());
    }

    /** Detached rows and the subscription token, taken under the same lock that registers the subscription. */
    TerminalRegistry.Bootstrap bootstrap(@NotNull String token) {
        return new TerminalRegistry.Bootstrap(token, view(), rows(), new HashMap<>(descriptions), unkeyed, recent);
    }

    StorageSnapshot snapshot() {
        return new StorageSnapshot(
            generation(),
            revision(),
            observedAt,
            true,
            true,
            true,
            rows(),
            descriptions,
            unkeyed);
    }

    @Nullable
    ResourceDescriptor description(@NotNull StableKey item) {
        return descriptions.get(item);
    }

    @Nullable
    RecentResourceActivity.Summary recent() {
        return recent;
    }

    /** A token identifies this topic lifetime and its current cache coverage, not only the native generation. */
    long recentToken() {
        return recentToken;
    }

    /** Reject late cache publication without waiting for a queue slot or rebuilding a summary. */
    void invalidateRecent() {
        recentToken = recentTokens.getAsLong();
        recent = null;
    }

    /** Published alongside stock; detached immutable summaries never require a history query. */
    void recent(long publicationToken, @NotNull RecentResourceActivity.Summary published) {
        if (recentToken != publicationToken) return;
        RecentResourceActivity.Summary summary = visibleRecent(published);
        RecentResourceActivity.Summary previous = recent;
        recent = summary;
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
        publish(Collections.emptySet(), Collections.emptySet(), false, changed, true);
    }

    private RecentResourceActivity.Summary visibleRecent(RecentResourceActivity.Summary summary) {
        Map<StableKey, RecentResourceActivity.Amount> visible = new HashMap<>();
        String flowZero = summary.flowSince() == null || !summary.flowComplete() ? null : "0";
        String stockZero = summary.stockSince() == null
            || Objects.equals(summary.stockSince(), summary.stockObservedAt())
            || !summary.stockComplete() ? null : "0";
        RecentResourceActivity.Amount zero = new RecentResourceActivity.Amount(
            flowZero,
            flowZero,
            flowZero,
            flowZero,
            stockZero);
        for (StableKey item : rows.keySet()) visible.put(
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

    @Override
    protected void invalidate() {
        super.invalidate();
        rows.clear();
        descriptions.clear();
        unkeyed = Collections.emptyList();
        recent = null;
        recentToken = recentTokens.getAsLong();
    }

    private List<StorageObservation.Row> rows() {
        return new ArrayList<>(rows.values());
    }

    @Override
    protected @NotNull LiveUpdate drain(@NotNull LiveSubscription.Pending pending) {
        List<StorageObservation.Row> definitions = new ArrayList<>();
        Map<StableKey, ResourceDescriptor> described = new HashMap<>();
        List<Amount> updates = new ArrayList<>();
        List<StableKey> removed = new ArrayList<>();
        for (StableKey key : pending.changed()) {
            StorageObservation.Row row = rows.get(key);
            if (row == null) removed.add(key);
            else {
                updates.add(new Amount(key, row.quantity(), row.craftable()));
                if (pending.definitions()
                    .contains(key)) {
                    definitions.add(row);
                    described.put(key, descriptions.get(key));
                }
            }
        }
        List<StorageObservation.UnkeyedRow> unknown = pending.unkeyedChanged() ? unkeyed : null;
        RecentResourceActivity.Summary summary = null;
        if (pending.summaryChanged() && recent != null) {
            Map<StableKey, RecentResourceActivity.Amount> amounts = new HashMap<>();
            for (StableKey key : pending.summaryKeys()) {
                RecentResourceActivity.Amount amount = recent.resources()
                    .get(key);
                if (amount != null) amounts.put(key, amount);
            }
            summary = recent.withResources(amounts);
        }
        return new Update(view(), updates, definitions, removed, described, unknown, summary);
    }
}
