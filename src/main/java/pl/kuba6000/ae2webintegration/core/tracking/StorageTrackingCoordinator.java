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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridFilter;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
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
    private final StorageTrackingService service;
    private final GridIdentityRegistry identities;
    private final ConfigSettings.ResourceTracking settings;
    private final Map<StableKey, Binding> bindings = new HashMap<>();
    private final List<Binding> scheduled = new ArrayList<>();
    private long nextDiscovery;
    private long generation = System.currentTimeMillis();
    private int cursor;

    public StorageTrackingCoordinator(@NotNull StorageTrackingService service, @NotNull GridIdentityRegistry identities,
        @NotNull ConfigSettings.ResourceTracking settings) {
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
            IAEGrid grid = binding.grid.get();
            if (grid != null && GridFilter.isUsable(grid)
                && binding.key.equals(identities.getKey(grid))
                && (nowMillis >= binding.nextObservation
                    || !binding.lastFailure && binding.capture.requiresReconciliation()
                        && nowMillis - binding.lastObservation >= settings.activeIntervalMillis)) {
                observe(binding, grid, nowMillis);
                break;
            }
        }
    }

    /** Called by an authorized terminal read on the game thread; the lease does not enable history. */
    public @Nullable StorageSnapshot observeForViewer(@NotNull IAEGrid grid, long nowMillis) {
        StableKey key = identities.getKey(grid);
        if (key == null || !GridFilter.isUsable(grid)) return null;
        Binding binding = binding(key, grid, enabled(key));
        boolean wasActive = binding.activeUntil > nowMillis;
        binding.activeUntil = nowMillis + VIEWER_LEASE_MILLIS;
        if (!wasActive) binding.nextObservation = Math.min(binding.nextObservation, nowMillis);
        StorageObservation observation = nowMillis >= binding.nextObservation ? observe(binding, grid, nowMillis)
            : null;
        StorageSnapshot snapshot = service.snapshot(key);
        if (snapshot != null && snapshot.revision() != 0 && snapshot.stockComplete()) return snapshot;
        // The first request can use its detached baseline without waiting for the background writer.
        if (observation != null && observation.full()) {
            return new StorageSnapshot(
                binding.generation,
                0,
                observation.observedAt(),
                true,
                observation.flowComplete(),
                snapshot == null || snapshot.persistenceComplete(),
                observation.rows());
        }
        return null;
    }

    private void discover(IAE ae, long nowMillis) {
        Set<StableKey> present = new HashSet<>();
        for (IAEGrid grid : ae.web$getGrids()) {
            if (!GridFilter.isUsable(grid)) continue;
            StableKey key = identities.getKey(grid);
            if (key == null) continue;
            Binding existing = bindings.get(key);
            boolean persist = enabled(key);
            if (!persist && (existing == null || existing.activeUntil <= nowMillis)) continue;
            binding(key, grid, persist);
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

    private boolean enabled(StableKey key) {
        GridPersistentData data = identities.getPersistentData(key);
        return data != null && data.getSettings()
            .isStorageTracked();
    }

    private Binding binding(StableKey key, IAEGrid grid, boolean persist) {
        Binding binding = bindings.get(key);
        if (binding == null || binding.grid.get() != grid || binding.persist != persist) {
            long activeUntil = binding == null ? 0 : binding.activeUntil;
            if (binding != null) {
                detach(binding);
                scheduled.remove(binding);
            }
            binding = new Binding(key, ++generation, grid, new StorageCapture(settings.maxResourcesPerGrid));
            binding.activeUntil = activeUntil;
            binding.persist = persist;
            bindings.put(key, binding);
            scheduled.add(binding);
            grid.web$getStorageGrid()
                .web$setStorageCapture(binding.capture);
            service.open(key, binding.generation);
        }
        return binding;
    }

    private @Nullable StorageObservation observe(Binding binding, IAEGrid grid, long nowMillis) {
        binding.lastObservation = nowMillis;
        binding.nextObservation = nowMillis + (binding.activeUntil > nowMillis ? settings.activeIntervalMillis
            : TimeUnit.SECONDS.toMillis(settings.idleIntervalSeconds));
        try {
            StorageObservation observation = binding.capture
                .observe(grid, null, nowMillis, nowMillis >= binding.nextFull);
            if (observation.full()) {
                binding.nextFull = nowMillis + TimeUnit.SECONDS.toMillis(settings.reconciliationIntervalSeconds);
            }
            if (!service.submit(binding.key, binding.generation, observation, binding.persist)) {
                binding.capture.markGap();
                binding.lastFailure = true;
                return null;
            }
            binding.lastFailure = false;
            return observation;
        } catch (RuntimeException failure) {
            binding.capture.markGap();
            if (!binding.lastFailure) LOG.warn("Storage observation failed for network {}", binding.key, failure);
            binding.lastFailure = true;
            return null;
        }
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
        private boolean persist;
        private boolean lastFailure;
        private long activeUntil;
        private long nextObservation;
        private long lastObservation;
        private long nextFull;

        private Binding(StableKey key, long generation, IAEGrid grid, StorageCapture capture) {
            this.key = key;
            this.generation = generation;
            this.grid = new WeakReference<>(grid);
            this.capture = capture;
        }
    }
}
