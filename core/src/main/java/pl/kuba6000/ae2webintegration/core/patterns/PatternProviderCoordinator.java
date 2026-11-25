package pl.kuba6000.ae2webintegration.core.patterns;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.grid.GridFilter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;

/** Observes viewed networks on the game thread; all viewers share the same completed observation. */
public final class PatternProviderCoordinator implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final long CHECK_MILLIS = SECONDS.toMillis(1);
    private static final long LIGHT_PASS_MILLIS = SECONDS.toMillis(30);
    private final LiveHub hub;
    private final PatternProviderRegistry registry;
    private final Function<StableKey, IAEGrid> resolve;
    private final BooleanSupplier supported;
    private final Map<StableKey, Binding> bindings = new HashMap<>();
    private long nextGeneration;

    public PatternProviderCoordinator(@NotNull LiveHub hub, @NotNull Function<StableKey, IAEGrid> resolve,
        @NotNull BooleanSupplier supported) {
        this.hub = hub;
        this.registry = new PatternProviderRegistry(hub);
        this.resolve = resolve;
        this.supported = supported;
    }

    public @NotNull PatternProviderRegistry registry() {
        return registry;
    }

    /** Static adapter capability, also safe to check while authorizing an HTTP request. */
    public boolean isSupported() {
        return supported.getAsBoolean();
    }

    /** Completes a first read on the server thread before the HTTP snapshot is reserved. */
    public @Nullable PatternProviderTopic prepare(@NotNull StableKey grid, @NotNull IAEGrid nativeGrid, long now) {
        if (!isSupported() || !GridFilter.isUsable(nativeGrid)) return null;
        Binding binding = bindings.get(grid);
        LiveTopic.Key key = PatternProviderTopic.keyOf(grid);
        boolean baseline;
        synchronized (hub) {
            hub.expire(now);
            if (binding == null || binding.grid.get() != nativeGrid || hub.topic(key) != binding.topic) {
                binding = new Binding(new PatternProviderTopic(grid, ++nextGeneration), nativeGrid);
                baseline = true;
            } else baseline = false;
            PatternProviderTopic topic = binding.topic;
            hub.activate(key, topic.generation(), now, () -> topic);
            if (hub.topic(key) != topic) return null;
            if (baseline) bindings.put(grid, binding);
        }
        if (baseline || now >= binding.nextCheck) {
            try {
                observe(binding, nativeGrid, now, baseline);
            } catch (RuntimeException failure) {
                discard(binding);
                throw failure;
            }
        }
        return binding.topic;
    }

    /** A refresh checks at most one viewed network per tick, oldest due first. */
    public void tick(long now) {
        if (bindings.isEmpty()) return;
        Set<LiveTopic.Key> viewing = hub.viewed(PatternProviderTopic.class, now);
        Binding due = null;
        for (Iterator<Binding> it = bindings.values()
            .iterator(); it.hasNext();) {
            Binding binding = it.next();
            if (!viewing.contains(binding.topic.key())) {
                // A completed topic may still be awaiting reservation by its HTTP worker.
                if (hub.topic(binding.topic.key()) != binding.topic) it.remove();
            } else if (now >= binding.nextCheck && (due == null || binding.nextCheck < due.nextCheck)) {
                due = binding;
            }
        }
        if (due == null) return;
        StableKey gridKey = due.topic.key()
            .grid();
        try {
            IAEGrid grid = due.grid.get();
            if (grid != null && resolve.apply(gridKey) == grid && GridFilter.isUsable(grid)) {
                observe(due, grid, now, false);
                return;
            }
        } catch (RuntimeException failure) {
            LOG.warn("Pattern providers could not be read on network {}", gridKey, failure);
        }
        discard(due);
    }

    private void observe(Binding binding, IAEGrid grid, long now, boolean baseline) {
        binding.nextCheck = now + CHECK_MILLIS;
        long revision = grid.web$getPatternRevision();
        if (!baseline && revision == binding.revision && now < binding.nextFullRead) return;
        long started = System.nanoTime();
        PatternProviderReader.Delta delta = binding.reader.read(grid);
        LOG.debug(
            "Pattern providers {} on grid {}: {} microseconds",
            baseline ? "first read" : "refresh",
            binding.topic.key()
                .grid(),
            NANOSECONDS.toMicros(System.nanoTime() - started));
        registry.observed(binding.topic, delta);
        binding.revision = revision;
        binding.nextFullRead = now + LIGHT_PASS_MILLIS;
    }

    private void discard(Binding binding) {
        bindings.remove(
            binding.topic.key()
                .grid());
        retire(binding);
    }

    private void retire(Binding binding) {
        hub.retire(binding.topic.key(), binding.topic.generation());
    }

    @Override
    public void close() {
        for (Binding binding : bindings.values()) retire(binding);
        bindings.clear();
    }

    private static final class Binding {

        private final PatternProviderTopic topic;
        private final WeakReference<IAEGrid> grid;
        private final PatternProviderReader reader = new PatternProviderReader();
        private long revision;
        private long nextCheck;
        private long nextFullRead;

        private Binding(PatternProviderTopic topic, IAEGrid grid) {
            this.topic = topic;
            this.grid = new WeakReference<>(grid);
        }
    }
}
