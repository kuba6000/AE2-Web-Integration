package pl.kuba6000.ae2webintegration.core.cpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.dto.CpuResource;
import pl.kuba6000.ae2webintegration.core.http.dto.GetCPUClusterData;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.live.LiveSubscription;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;
import pl.kuba6000.ae2webintegration.core.live.LiveUpdate;
import pl.kuba6000.ae2webintegration.core.live.LiveView;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * The resources of one crafting CPU's current job and its header. Running measurements are evaluated whenever a
 * snapshot or update is built, so that clients can extend them with their own clock instead of receiving every tick.
 */
public final class CpuTopic extends LiveTopic {

    private static final CpuObservation.Header IDLE = new CpuObservation.Header(
        0,
        false,
        null,
        false,
        false,
        null,
        false,
        0,
        Collections.emptyList(),
        null);
    private final Map<StableKey, CpuObservation.Row> rows = new HashMap<>();
    private final Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
    private List<CpuObservation.UnkeyedRow> unkeyed = Collections.emptyList();
    private CpuObservation.Header header = IDLE;

    /** Absolute work of one resource at the moment its update was built. */
    @Desugar
    private record Value(@NotNull StableKey itemKey, long active, long pending, long stored, long craftedTotal,
        long timeSpentCrafting, boolean crafting) {}

    @Desugar
    private record Delta(@NotNull LiveView view, @NotNull List<Value> updates, @NotNull List<CpuResource> definitions,
        @NotNull List<StableKey> removed, @Nullable List<CpuResource> unkeyed, @Nullable GetCPUClusterData cpu,
        @Nullable IconMappings icons) implements IResponseBody {}

    @Desugar
    private record Update(@NotNull LiveView view, long at, @NotNull List<Value> updates,
        @NotNull List<CpuObservation.Row> definitions, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<StableKey> removed, @Nullable List<CpuObservation.UnkeyedRow> unkeyed,
        @Nullable CpuObservation.Header header) implements LiveUpdate {

        @Override
        public boolean describes() {
            return !definitions.isEmpty() || unkeyed != null && !unkeyed.isEmpty()
                || header != null && header.finalOutput() != null;
        }

        @Override
        public @NotNull Delta body(@Nullable IconMappings mappings) {
            return new Delta(
                view,
                updates,
                resources(definitions, descriptions, Collections.emptyList(), at, mappings),
                removed,
                unkeyed == null ? null
                    : resources(Collections.emptyList(), Collections.emptyMap(), unkeyed, at, mappings),
                header == null ? null : new GetCPUClusterData(header, at, mappings),
                mappings);
        }
    }

    CpuTopic(@NotNull StableKey grid, @NotNull StableKey cpu, long generation) {
        super(keyOf(grid, cpu), generation);
    }

    public static @NotNull Key keyOf(@NotNull StableKey grid, @NotNull StableKey cpu) {
        return new Key(CpuTopic.class, grid, cpu);
    }

    /**
     * Resource rows with their measurements as of {@code at}, without derived ratios. Unkeyed rows follow keyed ones.
     */
    private static @NotNull List<CpuResource> resources(@NotNull List<CpuObservation.Row> rows,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, @NotNull List<CpuObservation.UnkeyedRow> unkeyed,
        long at, @Nullable IconMappings mappings) {
        List<CpuResource> result = new ArrayList<>(rows.size() + unkeyed.size());
        for (CpuObservation.Row row : rows) {
            result.add(new CpuResource(descriptions.get(row.itemKey()), row.amounts(), at, mappings));
        }
        for (CpuObservation.UnkeyedRow row : unkeyed) {
            result.add(new CpuResource(row.description(), row.amounts(), at, mappings));
        }
        return result;
    }

    /** The detail response's resource list: with derived timing ratios while tracked, active resources first. */
    public static @NotNull ArrayList<CpuResource> items(@NotNull List<CpuObservation.Row> rows,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, @NotNull List<CpuObservation.UnkeyedRow> unkeyed,
        @NotNull CpuObservation.Header header, long at, @Nullable IconMappings mappings) {
        ArrayList<CpuResource> items = new ArrayList<>(resources(rows, descriptions, unkeyed, at, mappings));
        if (header.hasTrackingInfo()) {
            List<CpuObservation.Amounts> amounts = new ArrayList<>(items.size());
            for (CpuObservation.Row row : rows) amounts.add(row.amounts());
            for (CpuObservation.UnkeyedRow row : unkeyed) amounts.add(row.amounts());
            deriveRatios(items, amounts, at, header.timeElapsed(at));
        }
        items.sort((i1, i2) -> {
            if (i1.active > 0 && i2.active > 0) return Long.compare(i2.active, i1.active);
            else if (i1.active > 0 && i2.active == 0) return -1;
            else if (i1.active == 0 && i2.active > 0) return 1;
            if (i1.pending > 0 && i2.pending > 0) return Long.compare(i2.pending, i1.pending);
            else if (i1.pending > 0 && i2.pending == 0) return -1;
            else if (i1.pending == 0 && i2.pending > 0) return 1;
            return Long.compare(i2.stored, i1.stored);
        });
        return items;
    }

    private static void deriveRatios(List<CpuResource> items, List<CpuObservation.Amounts> amounts, long at,
        long elapsed) {
        long total = 0;
        for (CpuObservation.Amounts row : amounts) total += row.timeSpent(at);
        for (int index = 0; index < items.size(); index++) {
            CpuObservation.Amounts row = amounts.get(index);
            if (row.tracking() == null) continue;
            CpuResource item = items.get(index);
            long spent = row.timeSpent(at);
            item.shareInCraftingTime = total == 0 ? 1d : (double) spent / (double) total;
            item.shareInCraftingTimeCombined = elapsed > 0 ? Math.min((double) spent / (double) elapsed, 1d) : 0d;
            item.craftsPerSec = spent > 0
                ? (double) row.craftedTotal() / (spent / (double) TimeUnit.SECONDS.toMillis(1))
                : 0d;
        }
    }

    /** Applies the observed resources; a full observation also finds removed ones and the first one initializes. */
    void observed(@NotNull CpuObservation observation) {
        if (!initialized() && !observation.full()) return;
        Set<StableKey> changed = new HashSet<>();
        Set<StableKey> definitions = new HashSet<>();
        Set<StableKey> remaining = observation.full() ? new HashSet<>(rows.keySet()) : null;
        for (CpuObservation.Row row : observation.rows()) {
            StableKey item = row.itemKey();
            if (remaining != null) remaining.remove(item);
            CpuObservation.Row previous = rows.put(item, row);
            ResourceDescriptor description = observation.descriptions()
                .get(item);
            ResourceDescriptor old = descriptions.put(item, description);
            if (!row.equals(previous)) changed.add(item);
            if (!description.equals(old)) {
                definitions.add(item);
                changed.add(item);
            }
        }
        for (StableKey item : observation.missing()) {
            if (rows.remove(item) == null) continue;
            descriptions.remove(item);
            changed.add(item);
        }
        if (remaining != null) for (StableKey item : remaining) {
            rows.remove(item);
            descriptions.remove(item);
            changed.add(item);
        }
        boolean unkeyedChanged = observation.full() && !observation.unkeyed()
            .equals(unkeyed);
        if (observation.full()) unkeyed = observation.unkeyed();
        CpuObservation.Header observed = observation.header();
        boolean headerChanged = !observed.equals(header);
        header = observed;
        boolean updated = !initialized() || !changed.isEmpty() || unkeyedChanged || headerChanged;
        markInitialized();
        if (!updated) return;
        publish(changed, definitions, unkeyedChanged, Collections.emptySet(), headerChanged);
    }

    /** Detached state and the subscription token, taken under the same lock that registers the subscription. */
    CpuRegistry.Bootstrap bootstrap(@NotNull String token, long at) {
        return new CpuRegistry.Bootstrap(
            token,
            view(),
            header,
            new ArrayList<>(rows.values()),
            new HashMap<>(descriptions),
            unkeyed,
            at);
    }

    @Override
    protected void invalidate() {
        super.invalidate();
        rows.clear();
        descriptions.clear();
        unkeyed = Collections.emptyList();
        header = IDLE;
    }

    @Override
    protected @NotNull LiveUpdate drain(@NotNull LiveSubscription.Pending pending) {
        long at = System.currentTimeMillis();
        List<Value> updates = new ArrayList<>();
        List<CpuObservation.Row> definitions = new ArrayList<>();
        Map<StableKey, ResourceDescriptor> described = new HashMap<>();
        List<StableKey> removed = new ArrayList<>();
        for (StableKey key : pending.changed()) {
            CpuObservation.Row row = rows.get(key);
            if (row == null) removed.add(key);
            else {
                CpuObservation.Amounts amounts = row.amounts();
                updates.add(
                    new Value(
                        key,
                        amounts.active(),
                        amounts.pending(),
                        amounts.stored(),
                        amounts.craftedTotal(),
                        amounts.timeSpent(at),
                        amounts.crafting()));
                if (pending.definitions()
                    .contains(key)) {
                    definitions.add(row);
                    described.put(key, descriptions.get(key));
                }
            }
        }
        return new Update(
            view(),
            at,
            updates,
            definitions,
            described,
            removed,
            pending.unkeyedChanged() ? unkeyed : null,
            pending.summaryChanged() ? header : null);
    }
}
