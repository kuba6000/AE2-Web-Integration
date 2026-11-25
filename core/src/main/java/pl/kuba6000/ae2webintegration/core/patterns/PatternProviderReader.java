package pl.kuba6000.ae2webintegration.core.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

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

/** One server-thread scan cursor per viewed network. Only completed scans publish detached rows. */
public final class PatternProviderReader implements AutoCloseable {

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
    private Map<StableKey, Cached> next = new LinkedHashMap<>();
    private final Map<StableKey, PatternProvider> replacements = new LinkedHashMap<>();
    private @Nullable Iterator<? extends IPatternProvider> providers;
    private @Nullable ProviderScan current;

    /** Starts a new pass. Replacing an unfinished pass discards it without publishing partial rows. */
    public void begin(@NotNull IAEGrid grid) {
        next.clear();
        replacements.clear();
        current = null;
        providers = grid.web$getPatternProviders()
            .iterator();
    }

    /** Reads at least one provider header or slot, then yields at the deadline. Null means resume next tick. */
    public @Nullable Delta step(long deadlineNanos, @NotNull LongSupplier nanoClock) {
        if (providers == null) throw new IllegalStateException("Pattern provider scan has not started");
        do {
            if (current == null) {
                if (!providers.hasNext()) {
                    Set<StableKey> removed = new LinkedHashSet<>(previous.keySet());
                    removed.removeAll(next.keySet());
                    previous = next;
                    next = new LinkedHashMap<>();
                    providers = null;
                    return new Delta(replacements, removed);
                }
                IPatternProvider provider = providers.next();
                PatternProvider.Header header = header(provider);
                if (next.containsKey(header.key())) {
                    throw new IllegalStateException(
                        "Ambiguous pattern provider address " + header.location() + " side " + header.side());
                }
                current = new ProviderScan(provider, header, previous.get(header.key()));
            } else {
                current.readSlot();
            }
            if (current.complete()) {
                Cached result = current.result();
                StableKey key = current.header.key();
                next.put(key, result);
                if (current.changed) replacements.put(key, result.row());
                current = null;
            }
        } while (nanoClock.getAsLong() - deadlineNanos < 0);
        return null;
    }

    /** Releases encodings and native handles, including an unfinished pass. */
    @Override
    public void close() {
        previous = Collections.emptyMap();
        next.clear();
        replacements.clear();
        providers = null;
        current = null;
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

    private static final class ProviderScan {

        private final IPatternProvider provider;
        private final PatternProvider.Header header;
        private final @Nullable Cached old;
        private final IPatternSlotHandle[] handles;
        private final List<PatternProvider.Slot> slots;
        private boolean changed;

        private ProviderScan(IPatternProvider provider, PatternProvider.Header header, @Nullable Cached old) {
            this.provider = provider;
            this.header = header;
            this.old = old;
            handles = new IPatternSlotHandle[provider.web$getPatternSlotCount()];
            slots = new ArrayList<>(handles.length);
            changed = old == null || !old.row()
                .header()
                .equals(header) || old.handles().length != handles.length;
        }

        private void readSlot() {
            int index = slots.size();
            IPatternSlotHandle handle = provider.web$getPattern(index);
            handles[index] = handle;
            if (old != null && index < old.handles().length) {
                IPatternSlotHandle before = old.handles()[index];
                if (handle == null ? before == null : before != null && handle.web$sameAs(before)) {
                    slots.add(
                        old.row()
                            .slots()
                            .get(index));
                    return;
                }
            }
            PatternProvider.Slot decoded = decode(handle);
            slots.add(decoded);
            changed |= old == null || index >= old.handles().length
                || !decoded.equals(
                    old.row()
                        .slots()
                        .get(index));
        }

        private boolean complete() {
            return slots.size() == handles.length;
        }

        private Cached result() {
            PatternProvider row = changed || old == null ? new PatternProvider(header, slots) : old.row();
            return new Cached(row, handles);
        }
    }
}
