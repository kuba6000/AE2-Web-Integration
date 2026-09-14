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

import com.github.bsideup.jabel.Desugar;
import com.google.gson.reflect.TypeToken;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** Persisted identities and weak live-grid bindings for one save; native reads run on the server thread. */
public final class GridIdentityRegistry {

    private @Nullable File file;
    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private final WeakHashMap<IAEGrid, StableKey> keys = new WeakHashMap<>();
    private Map<StableKey, GridRecord> records = new HashMap<>();
    private final Map<DimensionalCoords, StableKey> knownPositions = new HashMap<>();
    private static final GridSettingsData DEFAULT_SETTINGS = new GridSettingsData();

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
        GridAccessSessions.clear();
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
        return records.containsKey(key) ? key : null;
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

        Map<StableKey, GridRecord> next = new HashMap<>(records);
        for (StableKey key : previous) next.remove(key);
        // The first validated split component keeps the old key and claims only its current controllers.
        next.put(selected, new GridRecord(controllers, getSettings(selected)));
        try {
            publish(next);
            keys.put(grid, selected);
            for (StableKey key : previous) if (!key.equals(selected)) GridData.retire(key);
            GridAccessSessions.clear();
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
        } finally {
            GridAccessSessions.clear();
        }
    }

    private void load() throws IOException {
        File file = storageFile();
        if (Files.notExists(file.toPath())) return;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<StableKey, GridRecord> stored = GSONUtils.GSON_BUILDER.create()
                .fromJson(reader, new TypeToken<Map<StableKey, GridRecord>>() {}.getType());
            if (stored == null) throw new IOException("Empty grid identity file: " + file);
            for (Map.Entry<StableKey, GridRecord> entry : stored.entrySet()) {
                GridRecord record = entry.getValue();
                if (record == null || record.controllers() == null || record.settings() == null)
                    throw new IOException("Incomplete grid identity record: " + entry.getKey());
                for (DimensionalCoords position : record.controllers()) knownPositions.put(position, entry.getKey());
            }
            records = stored;
        } catch (RuntimeException e) {
            throw new IOException("Invalid grid identity file: " + file, e);
        }
    }

    private boolean preferIdentity(StableKey contender, StableKey winner) {
        if (getSettings(contender).isDefault() != getSettings(winner).isDefault())
            return !getSettings(contender).isDefault();
        return contender.toString()
            .compareTo(winner.toString()) < 0;
    }

    public synchronized boolean isTracked(@NotNull StableKey key) {
        return file != null && getSettings(key).isTracked();
    }

    public synchronized @NotNull GridSettingsData getSettings(@NotNull StableKey key) {
        checkState(file != null, "Grid identities are not initialized for this save");
        GridRecord record = records.get(key);
        return record == null ? DEFAULT_SETTINGS : record.settings();
    }

    public synchronized void setSettings(@NotNull StableKey key, @NotNull GridSettingsData value) throws IOException {
        checkState(file != null, "Grid identities are not initialized for this save");
        GridRecord current = records.get(key);
        if (current == null) throw new IllegalArgumentException("Unknown grid identity");
        Map<StableKey, GridRecord> next = new HashMap<>(records);
        next.put(key, new GridRecord(current.controllers(), value));
        publish(next);
    }

    /** Resolves only a retained association, without discovering native grids. */
    public synchronized @Nullable StableKey findIdentity(@NotNull DimensionalCoords position) {
        return knownPositions.get(position);
    }

    /** Includes retained unloaded identities, not just currently observed grids. */
    public synchronized boolean containsIdentity(@NotNull StableKey key) {
        return records.containsKey(key);
    }

    /** Updates a known identity; unknown keys cannot create phantom persisted grids. */
    public synchronized void setTracked(@NotNull StableKey key, boolean value) throws IOException {
        setSettings(key, new GridSettingsData(value));
    }

    /** Positive block destruction only; unload and missing observations must not call this method. */
    public synchronized void removeController(@NotNull DimensionalCoords position) throws IOException {
        StableKey key = knownPositions.get(position);
        if (key == null) return;
        Map<StableKey, GridRecord> next = new HashMap<>(records);
        GridRecord current = records.get(key);
        Set<DimensionalCoords> remaining = new LinkedHashSet<>(current.controllers());
        remaining.remove(position);
        if (remaining.isEmpty()) next.remove(key);
        else next.put(key, new GridRecord(remaining, current.settings()));
        publish(next);
    }

    private void publish(Map<StableKey, GridRecord> next) throws IOException {
        if (next.equals(records)) return;
        Map<StableKey, GridRecord> ordered = new TreeMap<>(Comparator.comparing(StableKey::toString));
        ordered.putAll(next);
        GSONUtils.writeAtomically(storageFile(), ordered);
        records = next;
        knownPositions.clear();
        records.forEach(
            (key, record) -> record.controllers()
                .forEach(position -> knownPositions.put(position, key)));
    }

    @Desugar
    private record GridRecord(Set<DimensionalCoords> controllers, GridSettingsData settings) {}
}
