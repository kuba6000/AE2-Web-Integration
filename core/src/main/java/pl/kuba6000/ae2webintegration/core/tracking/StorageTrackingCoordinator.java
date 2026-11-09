package pl.kuba6000.ae2webintegration.core.tracking;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridFilter;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentTrackingService;
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
    private final RecentTrackingService recent;
    private final TerminalRegistry terminals;
    private final GridIdentityRegistry identities;
    private ConfigSettings.ResourceTracking settings;
    private final Map<StableKey, Binding> bindings = new HashMap<>();
    private final List<Binding> scheduled = new ArrayList<>();
    private long nextDiscovery;
    private long nextViewerRefresh;
    private boolean discoveryFailed;
    private long generation = System.currentTimeMillis();
    private int cursor;

    public StorageTrackingCoordinator(@NotNull StorageTrackingService service, @NotNull GridIdentityRegistry identities,
        @NotNull ConfigSettings.ResourceTracking settings) {
        this(service, identities, settings, new TerminalRegistry(new LiveHub()));
    }

    public StorageTrackingCoordinator(@NotNull StorageTrackingService service, @NotNull GridIdentityRegistry identities,
        @NotNull ConfigSettings.ResourceTracking settings, @NotNull TerminalRegistry terminals) {
        this.terminals = terminals;
        this.service = service;
        this.identities = identities;
        this.settings = settings;
        recent = new RecentTrackingService(terminals, settings.maxQueuedRows);
    }

    /** Server-thread application preserves capture ownership, pending flow and live subscriptions. */
    public void applySettings(@NotNull ConfigSettings.ResourceTracking updated, long nowMillis) {
        if (settings == updated) return;
        for (Binding binding : bindings.values()) {
            binding.capture.setMaximumResources(updated.maxResourcesPerGrid);
            binding.nextViewerRead = Math.min(binding.nextViewerRead, nowMillis + updated.activeIntervalMillis);
            binding.nextFull = Math
                .min(binding.nextFull, nowMillis + TimeUnit.SECONDS.toMillis(updated.reconciliationIntervalSeconds));
        }
        service.setMaximumQueuedRows(updated.maxQueuedRows);
        recent.applyLimits(updated.maxQueuedRows, updated.maxResourcesPerGrid);
        settings = updated;
    }

    /** Off-thread read of an immutable worker publication; never touches native bindings or schedules work. */
    public @Nullable RecentResourceActivity.Summary recent(@NotNull StableKey key) {
        return recent.snapshot(key);
    }

    /** Native reads are shared across viewers; at most one scheduled grid observation runs per tick. */
    public void tick(@NotNull IAE ae, long nowMillis) {
        if (nowMillis >= nextViewerRefresh) {
            nextViewerRefresh = nowMillis + DISCOVERY_MILLIS;
            for (StableKey key : terminals.viewedGrids(nowMillis)) {
                IAEGrid grid = identities.getGrid(key);
                Binding existing = bindings.get(key);
                if (grid == null || backingOff(existing, nowMillis)) continue;
                long started = System.nanoTime();
                StableKey eligible;
                try {
                    eligible = eligibleKey(grid);
                } catch (RuntimeException failure) {
                    if (existing != null) failed(existing, failure, nowMillis, started);
                    else discoveryFailed(failure, nowMillis, started);
                    continue;
                }
                if (eligible != null) activate(binding(eligible, grid, gridSettings(eligible), nowMillis), nowMillis);
            }
        }
        if (nowMillis >= nextDiscovery) {
            nextDiscovery = nowMillis + DISCOVERY_MILLIS;
            discover(ae, nowMillis);
        }
        for (int visited = 0; visited < scheduled.size(); visited++) {
            if (cursor >= scheduled.size()) cursor = 0;
            Binding binding = scheduled.get(cursor++);
            IAEGrid grid = binding.grid.get();
            if (grid == null || !due(binding, nowMillis)) continue;
            long started = System.nanoTime();
            StableKey eligible;
            try {
                eligible = eligibleKey(grid);
            } catch (RuntimeException failure) {
                failed(binding, failure, nowMillis, started);
                continue;
            }
            if (binding.key.equals(eligible)) {
                observe(binding, grid, nowMillis);
                break;
            }
        }
    }

    /** Renews an authorized viewer lease without reading inventory or resource metadata. */
    public void touchViewer(@NotNull IAEGrid grid, long nowMillis) {
        StableKey key = identities.getKey(grid);
        if (key == null || !GridFilter.isUsable(grid)) return;
        Binding binding = binding(key, grid, gridSettings(key), nowMillis);
        activate(binding, nowMillis);
    }

    private void activate(Binding binding, long nowMillis) {
        boolean wasActive = binding.activeUntil > nowMillis;
        binding.activeUntil = nowMillis + VIEWER_LEASE_MILLIS;
        binding.capture.setStockNeeded(true);
        if (!binding.attached) return;
        if (terminals.activate(binding.key, binding.generation, nowMillis)) binding.capture.invalidate();
        if (!wasActive && binding.lastFailure == null)
            binding.nextViewerRead = Math.min(binding.nextViewerRead, nowMillis);
    }

    /** Called by an authorized terminal read on the game thread; the lease does not enable history. */
    public @Nullable StorageSnapshot observeForViewer(@NotNull IAEGrid grid, long nowMillis) {
        prepareForViewer(grid, nowMillis);
        StableKey key = identities.getKey(grid);
        return key == null ? null : terminals.snapshot(key);
    }

    /** Updates shared state without materializing a full response; the HTTP bootstrap reserves its own rows. */
    public void prepareForViewer(@NotNull IAEGrid grid, long nowMillis) {
        StableKey key = identities.getKey(grid);
        if (key == null || !GridFilter.isUsable(grid)) return;
        Binding binding = binding(key, grid, gridSettings(key), nowMillis);
        activate(binding, nowMillis);
        if (due(binding, nowMillis)) observe(binding, grid, nowMillis);
        if (binding.lastFailure != null) throw binding.lastFailure;
    }

    private void discover(IAE ae, long nowMillis) {
        List<IAEGrid> grids = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (IAEGrid grid : ae.web$getGrids()) grids.add(grid);
        } catch (RuntimeException failure) {
            discoveryFailed(failure, nowMillis, started);
            return;
        }
        Map<IAEGrid, Binding> byGrid = new IdentityHashMap<>();
        for (Binding binding : bindings.values()) {
            IAEGrid grid = binding.grid.get();
            if (grid != null) byGrid.put(grid, binding);
        }
        boolean failedDiscovery = false;
        Set<StableKey> present = new HashSet<>();
        for (IAEGrid grid : grids) {
            Binding known = byGrid.get(grid);
            if (backingOff(known, nowMillis)) {
                present.add(known.key);
                continue;
            }
            StableKey key;
            started = System.nanoTime();
            try {
                key = eligibleKey(grid);
            } catch (RuntimeException failure) {
                if (known != null) {
                    present.add(known.key);
                    failed(known, failure, nowMillis, started);
                } else {
                    discoveryFailed(failure, nowMillis, started);
                    failedDiscovery = true;
                }
                continue;
            }
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
        if (!failedDiscovery) discoveryFailed = false;
        Iterator<Map.Entry<StableKey, Binding>> iterator = bindings.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Map.Entry<StableKey, Binding> entry = iterator.next();
            if (!present.contains(entry.getKey())) {
                detach(entry.getValue(), nowMillis);
                iterator.remove();
            }
        }
        scheduled.clear();
        for (Binding binding : bindings.values()) if (binding.attached) scheduled.add(binding);
    }

    private @Nullable StableKey eligibleKey(IAEGrid grid) {
        return GridFilter.isUsable(grid) ? identities.getKey(grid) : null;
    }

    private static boolean backingOff(@Nullable Binding binding, long nowMillis) {
        return binding != null && binding.lastFailure != null && nowMillis < binding.retryAfter;
    }

    private void discoveryFailed(RuntimeException failure, long nowMillis, long started) {
        if (!discoveryFailed) LOG.warn("Storage network discovery failed", failure);
        discoveryFailed = true;
        nextDiscovery = retryAt(nowMillis, started, DISCOVERY_MILLIS);
    }

    private @Nullable GridSettingsData gridSettings(StableKey key) {
        GridPersistentData data = identities.getPersistentData(key);
        return data == null ? null : data.getSettings();
    }

    private Binding binding(StableKey key, IAEGrid grid, @Nullable GridSettingsData configuration, long nowMillis) {
        boolean stock = configuration != null && configuration.isStorageTracked();
        boolean flow = configuration != null && configuration.isFlowTracked();
        long stockInterval = TimeUnit.SECONDS.toMillis(
            configuration == null ? identities.getDataPolicy().stockSampleIntervalSeconds.defaultValue
                : configuration.getStockSampleIntervalSeconds());
        long flowInterval = TimeUnit.SECONDS.toMillis(
            configuration == null ? identities.getDataPolicy().flowFlushIntervalSeconds.defaultValue
                : configuration.getFlowFlushIntervalSeconds());
        Binding binding = bindings.get(key);
        if (binding == null || binding.grid.get() != grid || binding.stock != stock || binding.flow != flow) {
            long activeUntil = binding == null ? 0 : binding.activeUntil;
            if (binding != null) {
                detach(binding, nowMillis);
                scheduled.remove(binding);
            }
            binding = new Binding(
                key,
                ++generation,
                grid,
                new StorageCapture(grid, settings.maxResourcesPerGrid, flow));
            binding.activeUntil = activeUntil;
            binding.capture.setStockNeeded(stock || activeUntil > nowMillis);
            binding.stock = stock;
            binding.flow = flow;
            binding.nextFlowFlush = nowMillis + flowInterval;
            binding.nextFlowSample = nowMillis + RecentResourceActivity.BUCKET_MILLIS;
            binding.flowFrom = nowMillis;
            // Native installation may mutate one owner before failing on another. Retain the candidate for retry.
            bindings.put(key, binding);
        }
        if (binding.flowInterval != flowInterval) {
            binding.flowInterval = flowInterval;
            binding.nextFlowFlush = binding.flowFrom + flowInterval;
        }
        if (binding.stockInterval != stockInterval) {
            binding.stockInterval = stockInterval;
            binding.nextStockSample = 0;
        }
        if (!binding.attached && !backingOff(binding, nowMillis)) {
            long started = System.nanoTime();
            try {
                grid.web$getStorageGrid()
                    .web$setStorageCapture(binding.capture);
            } catch (RuntimeException failure) {
                failed(binding, failure, nowMillis, started);
                return binding;
            }
            binding.attached = true;
            scheduled.add(binding);
            if (stock || flow) {
                service.open(key, binding.generation);
                recent.open(key, binding.generation, stock, flow, settings.maxResourcesPerGrid, nowMillis);
            }
        }
        return binding;
    }

    private boolean due(Binding binding, long nowMillis) {
        if (!binding.attached || backingOff(binding, nowMillis)) return false;
        return binding.stock && nowMillis >= binding.nextStockSample
            || binding.flow && nowMillis >= binding.nextFlowFlush
            || binding.flow && nowMillis >= binding.nextFlowSample
            || binding.activeUntil > nowMillis && nowMillis >= binding.nextViewerRead
            || binding.flow && binding.activeUntil > nowMillis && nowMillis >= binding.nextRecentPublication;
    }

    private void observe(Binding binding, IAEGrid grid, long nowMillis) {
        binding.capture.setStockNeeded(binding.stock || binding.activeUntil > nowMillis);
        long started = System.nanoTime();
        boolean stockSample = binding.stock && nowMillis >= binding.nextStockSample;
        boolean flowFlush = binding.flow && nowMillis >= binding.nextFlowFlush;
        boolean flowSample = binding.flow && (flowFlush || nowMillis >= binding.nextFlowSample);
        boolean liveRead = binding.activeUntil > nowMillis && nowMillis >= binding.nextViewerRead;
        if (!stockSample && !flowSample && !liveRead) {
            publishRecent(binding, nowMillis);
            return;
        }
        try {
            long terminalEpoch = binding.capture.terminalEpoch();
            if (terminalEpoch != binding.terminalEpoch) {
                terminals.invalidate(binding.key, binding.generation);
                binding.terminalEpoch = terminalEpoch;
                recent.invalidate(binding.key, binding.generation, nowMillis);
            }
            StorageObservation observation = stockSample || liveRead
                ? binding.capture.observe(grid, nowMillis, stockSample || nowMillis >= binding.nextFull, flowSample)
                : binding.capture.flushFlow(nowMillis);
            if (observation.full())
                binding.nextFull = nowMillis + TimeUnit.SECONDS.toMillis(settings.reconciliationIntervalSeconds);
            // Native reads may themselves deliver a full-list invalidation.
            terminalEpoch = binding.capture.terminalEpoch();
            if (terminalEpoch != binding.terminalEpoch) {
                terminals.invalidate(binding.key, binding.generation);
                binding.terminalEpoch = terminalEpoch;
                recent.invalidate(binding.key, binding.generation, nowMillis);
                binding.capture.invalidate();
                // Preserve accepted flow, but this stock read crossed a native topology boundary.
                Map<StableKey, ResourceDescriptor> flowDescriptions = new HashMap<>();
                for (StorageObservation.Flow flow : observation.flows()) flowDescriptions.put(
                    flow.itemKey(),
                    observation.descriptions()
                        .get(flow.itemKey()));
                observation = new StorageObservation(
                    observation.observedAt(),
                    false,
                    observation.flowComplete(),
                    false,
                    Collections.emptyList(),
                    observation.flows(),
                    flowDescriptions,
                    Collections.emptyList());
                stockSample = false;
            }
            binding.accumulateFlow(observation);
            if (binding.activeUntil > nowMillis) terminals.observed(binding.key, binding.generation, observation);
            if (stockSample || flowSample)
                recent.submit(binding.key, binding.generation, observation, stockSample, flowSample);
            if (stockSample || flowFlush) {
                StorageObservation history = flowFlush ? binding.flushHistory(observation) : observation;
                // Persistence failure does not discard measurements already captured by tracking.
                submitHistory(binding, history, stockSample, flowFlush);
                if (flowFlush) binding.flowFrom = nowMillis;
            }
            if (binding.activeUntil > nowMillis) {
                if (stockSample || flowSample)
                    binding.nextRecentPublication = nowMillis + RecentResourceActivity.BUCKET_MILLIS;
                else if (nowMillis >= binding.nextRecentPublication || terminals.recent(binding.key) == null)
                    publishRecent(binding, nowMillis);
            }
            if (stockSample) binding.nextStockSample = nowMillis + binding.stockInterval;
            if (flowFlush) binding.nextFlowFlush = nowMillis + binding.flowInterval;
            if (flowSample) binding.nextFlowSample = nowMillis + RecentResourceActivity.BUCKET_MILLIS;
            if (observation.stockObserved()) binding.nextViewerRead = nowMillis + settings.activeIntervalMillis;
            binding.lastFailure = null;
        } catch (RuntimeException failure) {
            failed(binding, failure, nowMillis, started);
        }
    }

    private void failed(Binding binding, RuntimeException failure, long nowMillis, long started) {
        service.invalidate(binding.key, binding.generation);
        binding.capture.markGap();
        recent.invalidate(binding.key, binding.generation, nowMillis);
        terminals.invalidate(binding.key, binding.generation);
        if (binding.lastFailure == null) LOG.warn("Storage tracking failed for network {}", binding.key, failure);
        binding.lastFailure = failure;
        long interval = binding.activeUntil > nowMillis ? settings.activeIntervalMillis
            : binding.flow ? binding.flowInterval : binding.stockInterval;
        binding.retryAfter = retryAt(nowMillis, started, interval);
    }

    private void publishRecent(Binding binding, long nowMillis) {
        if (!binding.stock && !binding.flow) return;
        recent.refresh(binding.key, binding.generation, nowMillis);
        binding.nextRecentPublication = nowMillis + RecentResourceActivity.BUCKET_MILLIS;
    }

    private static long retryAt(long nowMillis, long startedNanos, long interval) {
        // A slow failed read must not consume its own retry delay while it blocks the game thread.
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return nowMillis + elapsed + Math.max(interval, FAILURE_RETRY_MILLIS);
    }

    private void submitHistory(Binding binding, StorageObservation observation, boolean stock, boolean flow) {
        service.submit(binding.key, binding.generation, observation, stock, flow, binding.flowFrom);
    }

    private void detach(Binding binding, long nowMillis) {
        if (binding.attached && binding.flow && binding.capture.isProducerThread()) {
            StorageObservation observation;
            try {
                observation = binding.capture.flushFlow(nowMillis);
            } catch (RuntimeException failure) {
                LOG.warn("Pending flow could not be captured for network {}", binding.key, failure);
                // Already detached amounts survive even when final native metadata cannot be read.
                observation = new StorageObservation(
                    nowMillis,
                    false,
                    false,
                    false,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyList());
            }
            binding.accumulateFlow(observation);
            submitHistory(binding, binding.flushHistory(observation), false, true);
        }
        IAEGrid grid = binding.grid.get();
        if (grid != null) {
            try {
                IAEStorageGrid storage = grid.web$getStorageGrid();
                if (storage.web$getStorageCapture() == binding.capture) {
                    storage.web$setStorageCapture(null);
                }
            } catch (RuntimeException failure) {
                LOG.warn("Native storage capture could not be detached for network {}", binding.key, failure);
            }
        }
        binding.capture.close();
        recent.retire(binding.key, binding.generation);
        service.retire(binding.key, binding.generation);
        terminals.retire(binding.key, binding.generation);
    }

    @Override
    public void close() {
        long nowMillis = System.currentTimeMillis();
        for (Binding binding : bindings.values()) detach(binding, nowMillis);
        bindings.clear();
        scheduled.clear();
        recent.close();
    }

    private static final class Binding {

        private final StableKey key;
        private final long generation;
        private final WeakReference<IAEGrid> grid;
        private final StorageCapture capture;
        private final Map<StableKey, PendingFlow> pendingFlow = new HashMap<>();
        private boolean pendingFlowComplete = true;
        private long nextRecentPublication;
        private boolean stock;
        private boolean flow;
        private boolean attached;
        private long stockInterval;
        private long flowInterval;
        private long nextStockSample;
        private long nextFlowFlush;
        private long nextFlowSample;
        private long flowFrom;
        private long nextViewerRead;
        private long retryAfter;
        private long terminalEpoch;
        private @Nullable RuntimeException lastFailure;
        private long activeUntil;
        private long nextFull;

        private Binding(StableKey key, long generation, IAEGrid grid, StorageCapture capture) {
            this.key = key;
            this.generation = generation;
            this.grid = new WeakReference<>(grid);
            this.capture = capture;
        }

        private void accumulateFlow(StorageObservation observation) {
            pendingFlowComplete &= observation.flowComplete();
            for (StorageObservation.Flow flow : observation.flows()) {
                PendingFlow pending = pendingFlow.computeIfAbsent(
                    flow.itemKey(),
                    key -> new PendingFlow(
                        observation.descriptions()
                            .get(key)));
                pending.input = addFlow(pending.input, flow.input());
                pending.output = addFlow(pending.output, flow.output());
                pending.inputOperations = addFlow(pending.inputOperations, flow.inputOperations());
                pending.outputOperations = addFlow(pending.outputOperations, flow.outputOperations());
            }
        }

        private long addFlow(long current, long amount) {
            if (Long.MAX_VALUE - current < amount) {
                pendingFlowComplete = false;
                return Long.MAX_VALUE;
            }
            return current + amount;
        }

        private StorageObservation flushHistory(StorageObservation observation) {
            List<StorageObservation.Flow> flows = new ArrayList<>(pendingFlow.size());
            Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>(observation.descriptions());
            for (Map.Entry<StableKey, PendingFlow> entry : pendingFlow.entrySet()) {
                PendingFlow pending = entry.getValue();
                flows.add(
                    new StorageObservation.Flow(
                        entry.getKey(),
                        pending.input,
                        pending.output,
                        pending.inputOperations,
                        pending.outputOperations));
                descriptions.put(entry.getKey(), pending.description);
            }
            StorageObservation history = new StorageObservation(
                observation.observedAt(),
                observation.full(),
                pendingFlowComplete,
                observation.stockObserved(),
                observation.rows(),
                flows,
                descriptions,
                observation.unkeyed());
            pendingFlow.clear();
            pendingFlowComplete = true;
            return history;
        }

    }

    private static final class PendingFlow {

        private final ResourceDescriptor description;
        private long input;
        private long output;
        private long inputOperations;
        private long outputOperations;

        private PendingFlow(ResourceDescriptor description) {
            this.description = description;
        }
    }

}
