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

/**
 * One physical pattern provider in a network.
 *
 * @param key       stable provider identity derived from dimension, position and side
 * @param name      displayed terminal group name
 * @param icon      resource displayed for the provider; null when unavailable
 * @param location  position and optional side of this provider
 * @param online    whether the provider is online
 * @param visible   whether the provider is visible in its native terminal
 * @param rowSize   number of physical slots in one terminal row
 * @param priority  native crafting priority; null when unavailable
 * @param sortValue native sort value for ordering providers within a group
 * @param slots     physical slots in index order, including empty slots
 * @example key AAAAAAAAAAAAAAAAAAAAAA
 * @example name Furnace
 * @example rowSize 9
 */
@Desugar
public record PatternProviderView(@NotNull StableKey key, @NotNull String name, @Nullable ResourceDescription icon,
    @NotNull Location location, boolean online, boolean visible, int rowSize, @Nullable Integer priority,
    long sortValue, @NotNull List<Slot> slots) {

    /**
     * @param dimension native dimension identifier; numeric on legacy versions
     * @param x         block X coordinate
     * @param y         block Y coordinate
     * @param z         block Z coordinate
     * @param side      mounted side, or null for a full block
     * @example dimension minecraft:overworld
     * @example x 120
     * @example y 64
     * @example z -32
     */
    @Desugar
    public record Location(@NotNull String dimension, int x, int y, int z, @Nullable String side) {}

    /**
     * @param kind    physical slot state: {@code empty} or {@code pattern}
     * @param pattern decoded summary; null for an empty slot
     * @example kind pattern
     */
    @Desugar
    public record Slot(@NotNull String kind, @Nullable Pattern pattern) {

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

    /**
     * @param kind    pattern type: {@code crafting}, {@code processing}, or {@code invalid} when decoding failed
     * @param inputs  condensed inputs per execution, in native resource units; empty for an invalid pattern
     * @param outputs condensed outputs per execution, in native resource units; empty for an invalid pattern
     * @example kind processing
     */
    @Desugar
    public record Pattern(@NotNull String kind, @NotNull List<ResourceStack> inputs,
        @NotNull List<ResourceStack> outputs) {}

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
