package pl.kuba6000.ae2webintegration.core.tracking;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridFilter;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

/** Game-thread eligibility, native capture ownership and shared observation cadence. */
public final class StorageTrackingCoordinator implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final long DISCOVERY_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long VIEWER_LEASE_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long FAILURE_RETRY_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private final StorageTrackingService service;
    private final Supplier<@Nullable IconPack> iconPack;
    private final GridIdentityRegistry identities;
    private final ConfigSettings.ResourceTracking settings;
    private final Map<StableKey, Binding> bindings = new HashMap<>();
    private final List<Binding> scheduled = new ArrayList<>();
    private long nextDiscovery;
    private long generation = System.currentTimeMillis();
    private int cursor;

    public StorageTrackingCoordinator(@NotNull StorageTrackingService service, @NotNull GridIdentityRegistry identities,
        @NotNull ConfigSettings.ResourceTracking settings) {
        this(service, identities, settings, () -> null);
    }

    public StorageTrackingCoordinator(@NotNull StorageTrackingService service, @NotNull GridIdentityRegistry identities,
        @NotNull ConfigSettings.ResourceTracking settings, @NotNull Supplier<@Nullable IconPack> iconPack) {
        this.iconPack = iconPack;
        this.service = service;
        this.identities = identities;
        this.settings = settings;
    }

    /** Native reads are shared across viewers; at most one scheduled grid observation runs per tick. */
    public void tick(@NotNull IAE ae, long nowMillis) {
        if (nowMillis >= nextDiscovery) {
            discover(ae, nowMillis);
            nextDiscovery = nowMillis + DISCOVERY_MILLIS;
        }
        for (int visited = 0; visited < scheduled.size(); visited++) {
            if (cursor >= scheduled.size()) cursor = 0;
            Binding binding = scheduled.get(cursor++);
            clearPublishedBaseline(binding);
            IAEGrid grid = binding.grid.get();
            if (grid != null && GridFilter.isUsable(grid)
                && binding.key.equals(identities.getKey(grid))
                && due(binding, nowMillis)) {
                observe(binding, grid, nowMillis);
                break;
            }
        }
    }

    /** Renews an authorized viewer lease without reading inventory or resource metadata. */
    public void touchViewer(@NotNull IAEGrid grid, long nowMillis, boolean icons) {
        StableKey key = identities.getKey(grid);
        if (key == null || !GridFilter.isUsable(grid)) return;
        Binding binding = binding(key, grid, gridSettings(key), nowMillis);
        activate(binding, nowMillis, icons);
    }

    private void activate(Binding binding, long nowMillis, boolean icons) {
        boolean wasActive = binding.activeUntil > nowMillis;
        binding.activeUntil = nowMillis + VIEWER_LEASE_MILLIS;
        boolean needsIcons = icons && binding.iconsUntil <= nowMillis;
        if (icons) binding.iconsUntil = nowMillis + VIEWER_LEASE_MILLIS;
        if (needsIcons) binding.capture.invalidate();
        if ((!wasActive || needsIcons) && binding.lastFailure == null)
            binding.nextViewerRead = Math.min(binding.nextViewerRead, nowMillis);
    }

    /** Called by an authorized terminal read on the game thread; the lease does not enable history. */
    public @Nullable StorageSnapshot observeForViewer(@NotNull IAEGrid grid, long nowMillis) {
        return observeForViewer(grid, nowMillis, false);
    }

    public @Nullable StorageSnapshot observeForViewer(@NotNull IAEGrid grid, long nowMillis, boolean icons) {
        StableKey key = identities.getKey(grid);
        if (key == null || !GridFilter.isUsable(grid)) return null;
        Binding binding = binding(key, grid, gridSettings(key), nowMillis);
        activate(binding, nowMillis, icons);
        StorageObservation observation = due(binding, nowMillis) ? observe(binding, grid, nowMillis) : null;
        if (binding.lastFailure != null) throw binding.lastFailure;
        StorageSnapshot snapshot = service.snapshot(key);
        if (snapshot != null && snapshot.revision() != 0 && snapshot.stockComplete()) {
            clearPublishedBaseline(binding);
            return snapshot;
        }
        // The first request can use its detached baseline without waiting for the background writer.
        if (observation != null && observation.full()) {
            binding.pendingBaseline = new StorageSnapshot(
                binding.generation,
                0,
                observation.observedAt(),
                true,
                observation.flowComplete(),
                snapshot == null || snapshot.persistenceComplete(),
                observation.rows());
        }
        return binding.pendingBaseline;
    }

    private void discover(IAE ae, long nowMillis) {
        Set<StableKey> present = new HashSet<>();
        for (IAEGrid grid : ae.web$getGrids()) {
            if (!GridFilter.isUsable(grid)) continue;
            StableKey key = identities.getKey(grid);
            if (key == null) continue;
            Binding existing = bindings.get(key);
            GridSettingsData configuration = gridSettings(key);
            boolean persist = configuration != null
                && (configuration.isStorageTracked() || configuration.isFlowTracked());
            if (!persist && (existing == null || existing.activeUntil <= nowMillis
                && (existing.lastFailure == null || nowMillis >= existing.retryAfter))) continue;
            binding(key, grid, configuration, nowMillis);
            present.add(key);
        }
        Iterator<Map.Entry<StableKey, Binding>> iterator = bindings.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Map.Entry<StableKey, Binding> entry = iterator.next();
            if (!present.contains(entry.getKey())) {
                detach(entry.getValue());
                iterator.remove();
            }
        }
        scheduled.clear();
        scheduled.addAll(bindings.values());
    }

    private @Nullable GridSettingsData gridSettings(StableKey key) {
        GridPersistentData data = identities.getPersistentData(key);
        return data == null ? null : data.getSettings();
    }

    private Binding binding(StableKey key, IAEGrid grid, @Nullable GridSettingsData configuration, long nowMillis) {
        boolean stock = configuration != null && configuration.isStorageTracked();
        boolean flow = configuration != null && configuration.isFlowTracked();
        long stockInterval = TimeUnit.SECONDS
            .toMillis(configuration == null ? 300 : configuration.getStockSampleIntervalSeconds());
        Binding binding = bindings.get(key);
        if (binding == null || binding.grid.get() != grid || binding.stock != stock || binding.flow != flow) {
            long activeUntil = binding == null ? 0 : binding.activeUntil;
            long iconsUntil = binding == null ? 0 : binding.iconsUntil;
            if (binding != null) {
                detach(binding);
                scheduled.remove(binding);
            }
            binding = new Binding(key, ++generation, grid, new StorageCapture(settings.maxResourcesPerGrid, flow));
            binding.activeUntil = activeUntil;
            binding.iconsUntil = iconsUntil;
            binding.stock = stock;
            binding.flow = flow;
            binding.nextFlowFlush = nowMillis + TimeUnit.SECONDS.toMillis(settings.flowFlushIntervalSeconds);
            bindings.put(key, binding);
            scheduled.add(binding);
            grid.web$getStorageGrid()
                .web$setStorageCapture(binding.capture);
            service.open(key, binding.generation);
        }
        if (binding.stockInterval != stockInterval) {
            binding.stockInterval = stockInterval;
            binding.nextStockSample = 0;
        }
        return binding;
    }

    private boolean due(Binding binding, long nowMillis) {
        if (binding.lastFailure != null && nowMillis < binding.retryAfter) return false;
        return binding.stock && nowMillis >= binding.nextStockSample
            || binding.flow && nowMillis >= binding.nextFlowFlush
            || binding.activeUntil > nowMillis && nowMillis >= binding.nextViewerRead;
    }

    private @Nullable StorageObservation observe(Binding binding, IAEGrid grid, long nowMillis) {
        long started = System.nanoTime();
        boolean rejected = false;
        boolean stockSample = binding.stock && nowMillis >= binding.nextStockSample;
        boolean flowFlush = binding.flow && nowMillis >= binding.nextFlowFlush;
        boolean liveRead = binding.activeUntil > nowMillis && nowMillis >= binding.nextViewerRead;
        long interval = binding.activeUntil > nowMillis ? settings.activeIntervalMillis
            : binding.flow ? TimeUnit.SECONDS.toMillis(settings.flowFlushIntervalSeconds) : binding.stockInterval;
        try {
            StorageSnapshot previous = service.snapshot(binding.key);
            boolean reconcile = previous != null && previous.generation() == binding.generation
                && (previous.revision() > 0 || previous.lossEpoch() > 0)
                && !previous.stockComplete();
            StorageObservation observation = stockSample || liveRead ? binding.capture.observe(
                grid,
                binding.iconsUntil > nowMillis ? iconPack.get() : null,
                nowMillis,
                nowMillis >= binding.nextFull || reconcile,
                flowFlush) : binding.capture.flushFlow(nowMillis);
            if (observation.full())
                binding.nextFull = nowMillis + TimeUnit.SECONDS.toMillis(settings.reconciliationIntervalSeconds);
            if (!service.submit(binding.key, binding.generation, observation, stockSample, flowFlush)) {
                rejected = true;
                throw new IllegalStateException("Storage observation queue has no capacity");
            }
            if (stockSample) binding.nextStockSample = nowMillis + binding.stockInterval;
            if (flowFlush)
                binding.nextFlowFlush = nowMillis + TimeUnit.SECONDS.toMillis(settings.flowFlushIntervalSeconds);
            if (observation.stockObserved()) binding.nextViewerRead = nowMillis + settings.activeIntervalMillis;
            binding.lastFailure = null;
            if (observation.full() && binding.activeUntil > nowMillis) {
                binding.pendingBaseline = new StorageSnapshot(
                    binding.generation,
                    0,
                    observation.observedAt(),
                    true,
                    observation.flowComplete(),
                    true,
                    observation.rows());
            }
            return observation;
        } catch (RuntimeException failure) {
            if (!rejected) service.invalidate(binding.key, binding.generation);
            binding.capture.markGap();
            binding.pendingBaseline = null;
            if (binding.lastFailure == null)
                LOG.warn("Storage observation failed for network {}", binding.key, failure);
            binding.lastFailure = failure;
            binding.retryAfter = retryAt(nowMillis, started, interval);
            return null;
        }
    }

    private void clearPublishedBaseline(Binding binding) {
        if (binding.pendingBaseline == null) return;
        StorageSnapshot published = service.snapshot(binding.key);
        if (published != null && published.generation() == binding.generation
            && published.stockComplete()
            && published.revision() != 0
            && published.observedAt() >= binding.pendingBaseline.observedAt()) binding.pendingBaseline = null;
    }

    private static long retryAt(long nowMillis, long startedNanos, long interval) {
        // A slow failed read must not consume its own retry delay while it blocks the game thread.
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return nowMillis + elapsed + Math.max(interval, FAILURE_RETRY_MILLIS);
    }

    private void detach(Binding binding) {
        IAEGrid grid = binding.grid.get();
        if (grid != null && grid.web$getStorageGrid()
            .web$getStorageCapture() == binding.capture) {
            grid.web$getStorageGrid()
                .web$setStorageCapture(null);
        }
        service.retire(binding.key, binding.generation);
    }

    @Override
    public void close() {
        for (Binding binding : bindings.values()) detach(binding);
        bindings.clear();
        scheduled.clear();
    }

    private static final class Binding {

        private final StableKey key;
        private final long generation;
        private final WeakReference<IAEGrid> grid;
        private final StorageCapture capture;
        private boolean stock;
        private boolean flow;
        private long stockInterval;
        private long nextStockSample;
        private long nextFlowFlush;
        private long nextViewerRead;
        private long retryAfter;
        private @Nullable StorageSnapshot pendingBaseline;
        private @Nullable RuntimeException lastFailure;
        private long activeUntil;
        private long iconsUntil;
        private long nextFull;

        private Binding(StableKey key, long generation, IAEGrid grid, StorageCapture capture) {
            this.key = key;
            this.generation = generation;
            this.grid = new WeakReference<>(grid);
            this.capture = capture;
        }
    }
}
