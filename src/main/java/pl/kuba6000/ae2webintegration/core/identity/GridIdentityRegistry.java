package pl.kuba6000.ae2webintegration.core.identity;

import static com.google.common.base.Preconditions.checkState;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.MapMaker;
import com.google.gson.reflect.TypeToken;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** Persisted identities and weak live-grid bindings for one save; native reads run on the server thread. */
public final class GridIdentityRegistry {

    private @Nullable File file;
    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private final WeakHashMap<IAEGrid, StableKey> keys = new WeakHashMap<>();
    private final Map<StableKey, IAEGrid> grids = new MapMaker().weakValues()
        .makeMap();
    private Map<StableKey, GridPersistentData> records = new HashMap<>();
    private final Map<DimensionalCoords, StableKey> knownPositions = new HashMap<>();

    /** Inactive until the server opens a save. */
    public GridIdentityRegistry() {}

    public synchronized void initialize(@NotNull File saveDirectory) throws IOException {
        clear();
        file = new File(saveDirectory, "ae2webintegration/grid-identities.json");
        try {
            load();
        } catch (IOException e) {
            clear();
            throw e;
        }
    }

    public synchronized void clear() {
        file = null;
        records.clear();
        knownPositions.clear();
        keys.clear();
        grids.clear();
    }

    private File storageFile() {
        if (file == null) throw new IllegalStateException("Grid identities are not initialized for this save");
        return file;
    }

    /** A failed load aborts construction, so callers cannot overwrite the unread source through this instance. */
    public GridIdentityRegistry(@NotNull File file) throws IOException {
        this.file = file;
        load();
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
        return file != null;
    }

    /** AE2 completed controller validation. Conflicted/controllerless grids leave both maps untouched. */
    public synchronized void controllerValidated(@NotNull IAEGrid grid) {
        if (file == null) return;
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
            data = new GridPersistentData(controllers, new GridSettingsData());
            data.attachLock(this);
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
        if (file == null) return;
        StableKey affected = findIdentity(position);
        if (affected == null) return;
        try {
            removeController(position);
            if (!containsIdentity(affected)) GridData.retire(affected);
        } catch (IOException e) {
            LOG.error("Failed to persist removed controller", e);
        }
    }

    private void load() throws IOException {
        File file = storageFile();
        if (Files.notExists(file.toPath())) return;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<StableKey, GridPersistentData> stored = GSONUtils.GSON_BUILDER.create()
                .fromJson(reader, new TypeToken<Map<StableKey, GridPersistentData>>() {}.getType());
            if (stored == null) throw new IOException("Empty grid identity file: " + file);
            for (Map.Entry<StableKey, GridPersistentData> entry : stored.entrySet()) {
                GridPersistentData record = entry.getValue();
                if (record == null) throw new IOException("Incomplete grid identity record: " + entry.getKey());
                record.attachLock(this);
                for (DimensionalCoords position : record.getControllers()) knownPositions.put(position, entry.getKey());
            }
            records = stored;
        } catch (RuntimeException e) {
            throw new IOException("Invalid grid identity file: " + file, e);
        }
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

    /** Flushes edited settings without replacing their objects or rebuilding controller indexes. */
    public synchronized void saveIfDirty() throws IOException {
        checkState(file != null, "Grid identities are not initialized for this save");
        for (GridPersistentData data : records.values()) {
            if (data.getSettings()
                .isDirty()) {
                writeRecords(records);
                return;
            }
        }
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
        Map<StableKey, GridPersistentData> ordered = new TreeMap<>(Comparator.comparing(StableKey::toString));
        ordered.putAll(data);
        GSONUtils.writeAtomically(storageFile(), ordered);
        data.values()
            .forEach(
                record -> record.getSettings()
                    .markSaved());
    }
}
