package pl.kuba6000.ae2webintegration.core.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IEncodedPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProvider;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternSlotHandle;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Reads a viewed network on the server thread. Only successful reads replace the cached encodings. */
public final class PatternProviderReader {

    private static final PatternProvider.Slot EMPTY = new PatternProvider.Slot(
        PatternProvider.Kind.EMPTY,
        Collections.emptyList(),
        Collections.emptyList());
    private static final PatternProvider.Slot INVALID = new PatternProvider.Slot(
        PatternProvider.Kind.INVALID,
        Collections.emptyList(),
        Collections.emptyList());

    @Desugar
    public record Delta(@NotNull Map<StableKey, PatternProvider> replacements, @NotNull Set<StableKey> removed) {

        public Delta {
            replacements = Collections.unmodifiableMap(new LinkedHashMap<>(replacements));
            removed = Collections.unmodifiableSet(new LinkedHashSet<>(removed));
        }
    }

    @Desugar
    private record Cached(PatternProvider row, IPatternSlotHandle[] handles) {}

    private Map<StableKey, Cached> previous = Collections.emptyMap();

    public @NotNull Delta read(@NotNull IAEGrid grid) {
        Map<StableKey, Cached> next = new LinkedHashMap<>();
        Map<StableKey, PatternProvider> replacements = new LinkedHashMap<>();
        for (IPatternProvider provider : grid.web$getPatternProviders()) {
            PatternProvider.Header header = header(provider);
            StableKey key = header.key();
            if (next.containsKey(key)) {
                throw new IllegalStateException(
                    "Ambiguous pattern provider address " + header.location() + " side " + header.side());
            }
            Cached old = previous.get(key);
            Cached result = readProvider(provider, header, old);
            next.put(key, result);
            if (old == null || result.row() != old.row()) replacements.put(key, result.row());
        }
        Set<StableKey> removed = new LinkedHashSet<>(previous.keySet());
        removed.removeAll(next.keySet());
        previous = next;
        return new Delta(replacements, removed);
    }

    private static Cached readProvider(IPatternProvider provider, PatternProvider.Header header, @Nullable Cached old) {
        IPatternSlotHandle[] handles = new IPatternSlotHandle[provider.web$getPatternSlotCount()];
        List<PatternProvider.Slot> slots = new ArrayList<>(handles.length);
        for (int i = 0; i < handles.length; i++) {
            IPatternSlotHandle handle = provider.web$getPattern(i);
            handles[i] = handle;
            if (old != null && i < old.handles().length && sameEncoding(handle, old.handles()[i])) {
                slots.add(
                    old.row()
                        .slots()
                        .get(i));
            } else {
                slots.add(decode(handle));
            }
        }
        PatternProvider row = old != null && old.row()
            .header()
            .equals(header)
            && old.row()
                .slots()
                .equals(slots) ? old.row() : new PatternProvider(header, slots);
        return new Cached(row, handles);
    }

    private static boolean sameEncoding(@Nullable IPatternSlotHandle current, @Nullable IPatternSlotHandle previous) {
        return current == null ? previous == null : previous != null && current.web$sameAs(previous);
    }

    private static PatternProvider.Header header(IPatternProvider provider) {
        DimensionalCoords location = provider.web$getLocation();
        String side = provider.web$getPatternSide();
        StableKey key = StableKey.create(out -> {
            StableKey.writeText(out, location.dimensionId());
            out.putInt(location.x())
                .putInt(location.y())
                .putInt(location.z());
            out.putBoolean(side != null);
            if (side != null) StableKey.writeText(out, side);
        });
        IAEKey icon = provider.web$getPatternIcon();
        return new PatternProvider.Header(
            key,
            provider.web$getName(),
            location,
            side,
            icon == null ? null : AE2Controller.itemIdentities.describe(icon),
            provider.web$isPatternProviderOnline(),
            provider.web$isPatternProviderVisible(),
            provider.web$getPatternRowSize(),
            provider.web$getPatternPriority(),
            provider.web$getPatternSortValue());
    }

    private static PatternProvider.Slot decode(@Nullable IPatternSlotHandle handle) {
        if (handle == null) return EMPTY;
        IEncodedPatternDetails details = handle.web$decode();
        return details == null ? INVALID
            : new PatternProvider.Slot(
                details.web$isCraftingPattern() ? PatternProvider.Kind.CRAFTING : PatternProvider.Kind.PROCESSING,
                resources(details.web$getCondensedInputs()),
                resources(details.web$getCondensedOutputs()));
    }

    private static List<ResourceSnapshot> resources(IAEGenericStack[] stacks) {
        List<ResourceSnapshot> result = new ArrayList<>(stacks.length);
        for (IAEGenericStack stack : stacks) {
            ResourceSnapshot value = ResourceSnapshot.capture(stack);
            if (value != null) result.add(value);
        }
        return result;
    }
}
