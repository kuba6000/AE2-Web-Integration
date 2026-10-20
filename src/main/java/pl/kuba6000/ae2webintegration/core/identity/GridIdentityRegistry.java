package pl.kuba6000.ae2webintegration.core.identity;

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.MapMaker;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.DataPolicy;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.persistence.WorldGridStore;

/** Persisted identities and weak live-grid bindings for one save; native reads run on the server thread. */
public final class GridIdentityRegistry {

    private @Nullable WorldGridStore store;
    private final Set<StableKey> dirtyKeys = new HashSet<>();
    private @NotNull DataPolicy dataPolicy = DataPolicy.from(Config.INSTANCE.data);
    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private final WeakHashMap<IAEGrid, StableKey> keys = new WeakHashMap<>();
    private final Map<StableKey, IAEGrid> grids = new MapMaker().weakValues()
        .makeMap();
    private Map<StableKey, GridPersistentData> records = new HashMap<>();
    private final Map<DimensionalCoords, StableKey> knownPositions = new HashMap<>();

    /** Inactive until the server opens a save. */
    public GridIdentityRegistry() {}

    public synchronized void initialize(@NotNull WorldDatabase database, @NotNull Path importSource)
        throws IOException {
        clear();
        dataPolicy = database.policy();
        WorldGridStore persistence = new WorldGridStore(database);
        Map<StableKey, GridPersistentData> loaded = persistence.load(importSource);
        store = persistence;
        records = loaded;
        records.forEach((key, data) -> {
            bindSettings(key, data);
            data.getControllers()
                .forEach(position -> knownPositions.put(position, key));
        });
    }

    public synchronized void clear() {
        store = null;
        dirtyKeys.clear();
        records.clear();
        knownPositions.clear();
        keys.clear();
        grids.clear();
    }

    /** Reads the validated binding, or null when unavailable, without discovering controllers or changing maps. */
    public synchronized @Nullable StableKey getKey(@NotNull IAEGrid grid) {
        IAEPathingGrid pathing = grid.web$getPathingGrid();
        if (pathing == null || pathing.web$getControllerState() != AEControllerState.CONTROLLER_ONLINE) return null;
        StableKey key = keys.get(grid);
        return records.containsKey(key) && grids.get(key) == grid ? key : null;
    }

    /** Thread-safe reverse lookup; reads bindings only, never native grid state. */
    public synchronized @Nullable IAEGrid getGrid(@NotNull StableKey key) {
        if (!records.containsKey(key)) return null;
        IAEGrid grid = grids.get(key);
        return grid != null && key.equals(keys.get(grid)) ? grid : null;
    }

    public synchronized boolean isInitialized() {
        return store != null;
    }

    /** AE2 completed controller validation. Conflicted/controllerless grids leave both maps untouched. */
    public synchronized void controllerValidated(@NotNull IAEGrid grid) {
        if (!isInitialized()) return;
        IAEPathingGrid pathing = grid.web$getPathingGrid();
        if (pathing == null || pathing.web$getControllerState() != AEControllerState.CONTROLLER_ONLINE) return;
        Set<DimensionalCoords> controllers = new LinkedHashSet<>(grid.web$getControllers());
        if (controllers.isEmpty()) return;

        Set<StableKey> previous = new HashSet<>();
        StableKey selected = null;
        for (DimensionalCoords controller : controllers) {
            StableKey key = knownPositions.get(controller);
            if (key != null && previous.add(key) && (selected == null || preferIdentity(key, selected))) selected = key;
        }
        if (selected == null) {
            do {
                selected = StableKey.random();
            } while (records.containsKey(selected));
        }

        Map<StableKey, GridPersistentData> next = new HashMap<>(records);
        for (StableKey key : previous) next.remove(key);
        // The first validated split component keeps the old key and claims only its current controllers.
        GridPersistentData current = records.get(selected);
        GridPersistentData data;
        if (current == null) {
            data = new GridPersistentData(controllers, new GridSettingsData(dataPolicy));
            bindSettings(selected, data);
        } else {
            data = current.withControllers(controllers);
        }
        next.put(selected, data);
        try {
            publish(next);
            StableKey oldKey = keys.put(grid, selected);
            if (oldKey != null && !oldKey.equals(selected) && grids.get(oldKey) == grid) grids.remove(oldKey);
            grids.put(selected, grid);
            for (StableKey key : previous) {
                if (!key.equals(selected)) {
                    grids.remove(key);
                    GridData.retire(key);
                }
            }

        } catch (IOException e) {
            LOG.error("Failed to persist validated grid identity", e);
        }
    }

    /** Called only for physical removal, never chunk unload or a grid-node rebuild. */
    public synchronized void controllerRemoved(@NotNull DimensionalCoords position) {
        if (!isInitialized()) return;
        StableKey affected = findIdentity(position);
        if (affected == null) return;
        try {
            removeController(position);
            if (!containsIdentity(affected)) GridData.retire(affected);
        } catch (IOException e) {
            LOG.error("Failed to persist removed controller", e);
        }
    }

    public synchronized @NotNull DataPolicy getDataPolicy() {
        return dataPolicy;
    }

    private void bindSettings(StableKey key, GridPersistentData data) {
        GridSettingsData settings = data.getSettings();
        boolean normalized = settings.applyPolicy(dataPolicy);
        if (normalized) dirtyKeys.add(key);
        data.attach(this, () -> {
            // Runs under this registry's monitor; retired or previous-save settings must not dirty persisted settings.
            GridPersistentData current = records.get(key);
            if (current != null && current.getSettings() == settings) {
                dirtyKeys.add(key);
            }
        });
    }

    private boolean preferIdentity(StableKey contender, StableKey winner) {
        boolean contenderDefault = records.get(contender)
            .getSettings()
            .isDefault();
        boolean winnerDefault = records.get(winner)
            .getSettings()
            .isDefault();
        if (contenderDefault != winnerDefault) return !contenderDefault;
        return contender.toString()
            .compareTo(winner.toString()) < 0;
    }

    /** Retained data is available even while unloaded; unknown or closed-save identities return null. */
    public synchronized @Nullable GridPersistentData getPersistentData(@NotNull StableKey key) {
        return records.get(key);
    }

    /** Commits changed settings without rebuilding controller indexes. */
    public synchronized void saveIfDirty() throws IOException {
        checkState(isInitialized(), "Grid identities are not initialized for this save");
        if (!dirtyKeys.isEmpty()) writeRecords(records);
    }

    /** Resolves only a retained association, without discovering native grids. */
    public synchronized @Nullable StableKey findIdentity(@NotNull DimensionalCoords position) {
        return knownPositions.get(position);
    }

    /** Includes retained unloaded identities, not just currently observed grids. */
    public synchronized boolean containsIdentity(@NotNull StableKey key) {
        return records.containsKey(key);
    }

    /** Positive block destruction only; unload and missing observations must not call this method. */
    public synchronized void removeController(@NotNull DimensionalCoords position) throws IOException {
        StableKey key = knownPositions.get(position);
        if (key == null) return;
        Map<StableKey, GridPersistentData> next = new HashMap<>(records);
        GridPersistentData current = records.get(key);
        Set<DimensionalCoords> remaining = new LinkedHashSet<>(current.getControllers());
        remaining.remove(position);
        if (remaining.isEmpty()) next.remove(key);
        else next.put(key, current.withControllers(remaining));
        publish(next);
    }

    private void publish(Map<StableKey, GridPersistentData> next) throws IOException {
        if (next.equals(records)) {
            saveIfDirty();
            return;
        }
        writeRecords(next);
        records = next;
        knownPositions.clear();
        records.forEach(
            (key, data) -> data.getControllers()
                .forEach(position -> knownPositions.put(position, key)));
    }

    private void writeRecords(Map<StableKey, GridPersistentData> data) throws IOException {
        WorldGridStore persistence = store;
        if (persistence == null) throw new IOException("Grid identities are not initialized for this save");
        persistence.save(records, data, dirtyKeys);
        dirtyKeys.clear();
    }
}
