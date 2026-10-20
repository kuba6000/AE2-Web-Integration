package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.persistence.WorldGridStore;

/** Real world owners for isolated history tests; identities are created through the shared persistence seam. */
final class WorldHistoryFixtures {

    private static final Map<HsqlStorageHistoryStore, WorldDatabase> OWNERS = Collections
        .synchronizedMap(new WeakHashMap<>());
    private static final Map<HsqlStorageHistoryStore, Path> IMPORTS = Collections.synchronizedMap(new WeakHashMap<>());

    private WorldHistoryFixtures() {}

    static HsqlStorageHistoryStore create(Path path, ConfigSettings.Range retention, int maxRows) {
        if (maxRows < 1) throw new IllegalArgumentException("Row limit must be positive");
        ConfigSettings.Data policy = new ConfigSettings.Data();
        policy.historyRetentionDays = retention;
        policy.historyMaxRowsPerGrid = maxRows;
        WorldDatabase database = new WorldDatabase(path, policy);
        HsqlStorageHistoryStore store = new HsqlStorageHistoryStore(database);
        OWNERS.put(store, database);
        IMPORTS.put(store, path.resolveSibling("absent-import.json"));
        return store;
    }

    static void register(HsqlStorageHistoryStore store, StableKey key) throws IOException {
        WorldDatabase owner = OWNERS.get(store);
        WorldGridStore persistence = new WorldGridStore(owner);
        Map<StableKey, GridPersistentData> previous = persistence.load(IMPORTS.get(store));
        if (previous.containsKey(key)) return;
        HashMap<StableKey, GridPersistentData> next = new HashMap<>(previous);
        next.put(
            key,
            new GridPersistentData(
                Collections.singleton(new DimensionalCoords(key.toString(), 0, 0, 0)),
                new GridSettingsData(owner.policy())));
        persistence.save(previous, next, Collections.emptySet());
    }

    static void retention(HsqlStorageHistoryStore store, StableKey key, int days) throws IOException {
        WorldGridStore persistence = new WorldGridStore(OWNERS.get(store));
        Map<StableKey, GridPersistentData> records = persistence.load(IMPORTS.get(store));
        records.get(key)
            .getSettings()
            .setHistoryRetentionDays(days);
        persistence.save(records, records, Collections.singleton(key));
    }
}
