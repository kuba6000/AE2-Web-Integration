package pl.kuba6000.ae2webintegration.core.patterns;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.dto.ResourceDescription;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceStack;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Detached wire rows for a snapshot or a live update, using the response's icon mappings. */
@Desugar
public record PatternProviderView(StableKey key, String name, @Nullable ResourceDescription icon, Location location,
    boolean online, boolean visible, int rowSize, @Nullable Integer priority, long sortValue, List<Slot> slots) {

    @Desugar
    public record Location(String dimension, int x, int y, int z, @Nullable String side) {}

    @Desugar
    public record Slot(String kind, @Nullable Pattern pattern) {

        private static final Slot EMPTY = new Slot("empty", null);

        static Slot from(PatternProvider.Slot slot, @Nullable IconMappings icons) {
            if (slot.kind() == PatternProvider.Kind.EMPTY) return EMPTY;
            Pattern pattern = new Pattern(
                slot.kind()
                    .name()
                    .toLowerCase(Locale.ROOT),
                resources(slot.inputs(), icons),
                resources(slot.outputs(), icons));
            return new Slot("pattern", pattern);
        }
    }

    @Desugar
    public record Pattern(String kind, List<ResourceStack> inputs, List<ResourceStack> outputs) {}

    public static @NotNull List<PatternProviderView> rows(@NotNull List<PatternProvider> providers,
        @Nullable IconMappings icons) {
        List<PatternProviderView> rows = new ArrayList<>(providers.size());
        for (PatternProvider provider : providers) {
            PatternProvider.Header h = provider.header();
            List<Slot> slots = new ArrayList<>(
                provider.slots()
                    .size());
            for (PatternProvider.Slot slot : provider.slots()) slots.add(Slot.from(slot, icons));
            rows.add(
                new PatternProviderView(
                    h.key(),
                    h.name(),
                    h.icon() == null ? null : new ResourceDescription(h.icon(), icons),
                    new Location(
                        h.location()
                            .dimensionId(),
                        h.location()
                            .x(),
                        h.location()
                            .y(),
                        h.location()
                            .z(),
                        h.side()),
                    h.online(),
                    h.visible(),
                    h.rowSize(),
                    h.priority(),
                    h.sortValue(),
                    slots));
        }
        return rows;
    }

    private static List<ResourceStack> resources(List<ResourceSnapshot> snapshots, @Nullable IconMappings icons) {
        List<ResourceStack> stacks = new ArrayList<>(snapshots.size());
        for (ResourceSnapshot snapshot : snapshots) stacks.add(new ResourceStack(snapshot, icons));
        return stacks;
    }
}
