package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.config.*;
import pl.kuba6000.ae2webintegration.core.grid.*;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.*;

/** Real world owners for isolated history tests; identities are created through the shared persistence seam. */
final class WorldHistoryFixtures {

    private static final Map<HsqlStorageHistoryStore, WorldDatabase> OWNERS = Collections
        .synchronizedMap(new WeakHashMap<>());
    private static final Map<HsqlStorageHistoryStore, Path> IMPORTS = Collections.synchronizedMap(new WeakHashMap<>());

    private WorldHistoryFixtures() {}

    static HsqlStorageHistoryStore create(Path path, DataPolicy.Range retention, int maxRows) {
        if (maxRows < 1) throw new IllegalArgumentException("Row limit must be positive");
        var defaults = DataPolicy.from(new ConfigSettings.Data());
        var database = new WorldDatabase(
            path,
            new DataPolicy(
                defaults.stockSampleIntervalSeconds(),
                defaults.flowFlushIntervalSeconds(),
                retention,
                maxRows));
        var store = new HsqlStorageHistoryStore(database);
        OWNERS.put(store, database);
        IMPORTS.put(store, path.resolveSibling("absent-import.json"));
        return store;
    }

    static void register(HsqlStorageHistoryStore store, StableKey key) throws IOException {
        var owner = OWNERS.get(store);
        var persistence = new WorldGridStore(owner);
        var previous = persistence.load(IMPORTS.get(store));
        if (previous.containsKey(key)) return;
        var next = new HashMap<>(previous);
        next.put(
            key,
            new GridPersistentData(
                Collections.singleton(new DimensionalCoords(key.toString(), 0, 0, 0)),
                new GridSettingsData(owner.policy())));
        persistence.save(previous, next, Collections.emptySet());
    }

    static void retention(HsqlStorageHistoryStore store, StableKey key, int days) throws IOException {
        var persistence = new WorldGridStore(OWNERS.get(store));
        var records = persistence.load(IMPORTS.get(store));
        records.get(key)
            .getSettings()
            .setHistoryRetentionDays(days);
        persistence.save(records, records, Collections.singleton(key));
    }
}
