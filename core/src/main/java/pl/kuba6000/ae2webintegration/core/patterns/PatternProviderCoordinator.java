package pl.kuba6000.ae2webintegration.core.patterns;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.grid.GridFilter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;

/** Observes viewed networks on the game thread; all viewers share one cursor and one tick budget. */
public final class PatternProviderCoordinator implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final long BUDGET_NANOS = MILLISECONDS.toNanos(2);
    private static final long CHECK_MILLIS = SECONDS.toMillis(1);
    private static final long LIGHT_PASS_MILLIS = SECONDS.toMillis(30);
    private final LiveHub hub;
    private final PatternProviderRegistry registry;
    private final Function<StableKey, IAEGrid> resolve;
    private final BooleanSupplier supported;
    private final LongSupplier nanoClock;
    private final Map<StableKey, Binding> bindings = new HashMap<>();
    private long nextGeneration;
    private long turn;

    public PatternProviderCoordinator(@NotNull LiveHub hub, @NotNull Function<StableKey, IAEGrid> resolve,
        @NotNull BooleanSupplier supported, @NotNull LongSupplier nanoClock) {
        this.hub = hub;
        this.registry = new PatternProviderRegistry(hub);
        this.resolve = resolve;
        this.supported = supported;
        this.nanoClock = nanoClock;
    }

    public @NotNull PatternProviderRegistry registry() {
        return registry;
    }

    /** Static adapter capability, also safe to check while authorizing an HTTP request. */
    public boolean isSupported() {
        return supported.getAsBoolean();
    }

    /** Game thread after access checks. Opening does no native enumeration. Null means unsupported or at capacity. */
    public @Nullable PatternProviderRegistry.Opening prepare(@NotNull StableKey grid, @NotNull IAEGrid nativeGrid,
        long now) {
        if (!isSupported()) return null;
        Binding binding = bindings.get(grid);
        if (binding != null && (binding.grid.get() != nativeGrid || !registry.exists(grid, binding.generation, now))) {
            retire(binding);
            bindings.remove(grid);
            binding = null;
        }
        long generation = binding == null ? ++nextGeneration : binding.generation;
        PatternProviderRegistry.Opening opening = registry.open(grid, generation, now);
        if (opening != null) {
            if (binding == null) bindings.put(grid, new Binding(grid, nativeGrid, generation));
            else if (opening.resuming && binding.scanning) binding.recheckRevision = true;
        }
        return opening;
    }

    /** One fair slice per tick, never a separate budget per viewer. No native calls without demand. */
    public void tick(long now) {
        if (bindings.isEmpty()) return;
        Set<LiveTopic.Key> viewing = hub.viewed(PatternProviderTopic.class, now);
        Binding due = null;
        for (Iterator<Binding> it = bindings.values()
            .iterator(); it.hasNext();) {
            Binding binding = it.next();
            PatternProviderRegistry.Demand demand = registry.demand(binding.key.grid(), binding.generation, now);
            if (demand == PatternProviderRegistry.Demand.PAUSED) continue;
            if (!viewing.contains(binding.key) && demand == PatternProviderRegistry.Demand.NONE) {
                it.remove();
                retire(binding);
            } else if ((binding.scanning || now >= binding.nextCheck)
                && (due == null || binding.lastTurn < due.lastTurn)) {
                    due = binding;
                }
        }
        if (due == null) return;
        due.lastTurn = ++turn;
        try {
            IAEGrid grid = due.grid.get();
            if (grid != null && resolve.apply(due.key.grid()) == grid && GridFilter.isUsable(grid)) {
                observe(due, grid, now);
                return;
            }
        } catch (RuntimeException failure) {
            LOG.warn("Pattern providers could not be read on network {}", due.key.grid(), failure);
        }
        bindings.remove(due.key.grid());
        retire(due);
    }

    private void observe(Binding binding, IAEGrid grid, long now) {
        long deadline = nanoClock.getAsLong() + BUDGET_NANOS;
        if (binding.recheckRevision) {
            binding.recheckRevision = false;
            long revision = grid.web$getPatternRevision();
            if (binding.revision != revision) {
                binding.revision = revision;
                binding.reader.begin(grid);
            }
        }
        if (!binding.scanning) {
            binding.nextCheck = now + CHECK_MILLIS;
            long revision = grid.web$getPatternRevision();
            if (revision == binding.revision && now < binding.nextLightPass) return;
            binding.revision = revision;
            binding.reader.begin(grid);
            binding.scanning = true;
        }
        PatternProviderReader.Delta delta = binding.reader.step(deadline, nanoClock);
        if (delta == null) return;
        binding.scanning = false;
        binding.nextLightPass = now + LIGHT_PASS_MILLIS;
        registry.observed(binding.key.grid(), binding.generation, delta);
    }

    private void retire(Binding binding) {
        hub.retire(binding.key, binding.generation);
        binding.reader.close();
    }

    @Override
    public void close() {
        for (Binding binding : bindings.values()) retire(binding);
        bindings.clear();
    }

    private static final class Binding {

        private final LiveTopic.Key key;
        private final WeakReference<IAEGrid> grid;
        private final long generation;
        private final PatternProviderReader reader = new PatternProviderReader();
        private long revision;
        private long nextCheck;
        private long nextLightPass;
        private long lastTurn;
        private boolean scanning;
        private boolean recheckRevision;

        private Binding(StableKey gridKey, IAEGrid grid, long generation) {
            key = PatternProviderTopic.keyOf(gridKey);
            this.grid = new WeakReference<>(grid);
            this.generation = generation;
        }
    }
}
