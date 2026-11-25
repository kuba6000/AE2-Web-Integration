package pl.kuba6000.ae2webintegration.core.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Detached from native state; the index in slots identifies the physical slot. */
@Desugar
public record PatternProvider(@NotNull Header header, @NotNull List<Slot> slots) {

    public PatternProvider {
        slots = immutable(slots);
    }

    @Desugar
    public record Header(@NotNull StableKey key, @NotNull String name, @NotNull DimensionalCoords location,
        @Nullable String side, @Nullable ResourceDescriptor icon, boolean online, boolean visible, int rowSize,
        @Nullable Integer priority, long sortValue) {}

    public enum Kind {
        EMPTY,
        INVALID,
        CRAFTING,
        PROCESSING
    }

    @Desugar
    public record Slot(@NotNull Kind kind, @NotNull List<ResourceSnapshot> inputs,
        @NotNull List<ResourceSnapshot> outputs) {

        public Slot {
            inputs = immutable(inputs);
            outputs = immutable(outputs);
        }
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
