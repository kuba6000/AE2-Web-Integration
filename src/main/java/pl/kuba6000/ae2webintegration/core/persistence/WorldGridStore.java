package pl.kuba6000.ae2webintegration.core.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.config.DataPolicy;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Durable identity transactions share the world writer without entering the history queue. */
public final class WorldGridStore {

    private final WorldDatabase database;

    public WorldGridStore(@NotNull WorldDatabase database) {
        this.database = database;
    }

    public @NotNull Map<StableKey, GridPersistentData> load(@NotNull Path importSource) throws IOException {
        return database.write(db -> {
            boolean imported;
            try (Statement query = db.createStatement();
                ResultSet rows = query
                    .executeQuery("SELECT identities_imported FROM world_schema WHERE singleton = 1")) {
                rows.next();
                imported = rows.getBoolean(1);
            }
            if (!imported) {
                Map<StableKey, GridPersistentData> records = GridIdentityImport.read(importSource, database.policy());
                save(db, Collections.emptyMap(), records, records.keySet());
                try (Statement update = db.createStatement()) {
                    update.executeUpdate("UPDATE world_schema SET identities_imported = TRUE WHERE singleton = 1");
                }
            }
            return readRecords(db);
        });
    }

    static void normalizePolicy(Connection db, DataPolicy policy) throws SQLException {
        normalize(db, "stock_seconds", policy.stockSampleIntervalSeconds());
        normalize(db, "flow_seconds", policy.flowFlushIntervalSeconds());
        normalize(db, "retention_days", policy.historyRetentionDays());
    }

    private static void normalize(Connection db, String column, DataPolicy.Range range) throws SQLException {
        try (PreparedStatement update = db.prepareStatement("""
            UPDATE grids
            SET $column = CASE
                WHEN $column < ? THEN CAST(? AS INTEGER)
                ELSE CAST(? AS INTEGER)
            END
            WHERE $column < ? OR $column > ?
            """.replace("$column", column))) {
            update.setInt(1, range.min());
            update.setInt(2, range.min());
            update.setInt(3, range.max());
            update.setInt(4, range.min());
            update.setInt(5, range.max());
            update.executeUpdate();
        }
    }

    private Map<StableKey, GridPersistentData> readRecords(Connection db) throws SQLException {
        Map<StableKey, GridPersistentData> result = new HashMap<>();
        Map<StableKey, Set<DimensionalCoords>> controllers = new HashMap<>();
        Map<StableKey, GridSettingsData> settings = new HashMap<>();
        try (Statement query = db.createStatement(); ResultSet rows = query.executeQuery("""
            SELECT g.*, c.dimension_id, c.x, c.y, c.z
            FROM grids g
            JOIN grid_controllers c ON c.grid_id = g.id
            """)) {
            while (rows.next()) {
                StableKey key = StableKey.parse(rows.getString("grid_key"));
                if (!settings.containsKey(key)) {
                    GridSettingsData value = new GridSettingsData(database.policy());
                    value.setName(rows.getString("name"));
                    value.setTracked(rows.getBoolean("crafting_tracked"));
                    value.setStorageTracked(rows.getBoolean("stock_tracked"));
                    value.setFlowTracked(rows.getBoolean("flow_tracked"));
                    value.setStockSampleIntervalSeconds(rows.getInt("stock_seconds"));
                    value.setFlowFlushIntervalSeconds(rows.getInt("flow_seconds"));
                    value.setHistoryRetentionDays(rows.getInt("retention_days"));
                    settings.put(key, value);
                }
                controllers.computeIfAbsent(key, unused -> new LinkedHashSet<>())
                    .add(
                        new DimensionalCoords(
                            rows.getString("dimension_id"),
                            rows.getInt("x"),
                            rows.getInt("y"),
                            rows.getInt("z")));
            }
        }
        settings.forEach((key, value) -> result.put(key, new GridPersistentData(controllers.get(key), value)));
        return result;
    }

    public void save(@NotNull Map<StableKey, GridPersistentData> previous,
        @NotNull Map<StableKey, GridPersistentData> next, @NotNull Set<StableKey> dirty) throws IOException {
        database.write(db -> {
            save(db, previous, next, dirty);
            return null;
        });
    }

    private static void save(Connection db, Map<StableKey, GridPersistentData> previous,
        Map<StableKey, GridPersistentData> next, Set<StableKey> dirty) throws SQLException {
        Set<StableKey> changed = new HashSet<>();
        if (previous != next) {
            previous.forEach((key, value) -> {
                if (!next.containsKey(key) || !value.getControllers()
                    .equals(
                        next.get(key)
                            .getControllers()))
                    changed.add(key);
            });
            next.forEach((key, value) -> { if (!previous.containsKey(key)) changed.add(key); });
        }
        try (PreparedStatement delete = db.prepareStatement("""
            DELETE FROM grid_controllers
            WHERE grid_id = (SELECT id FROM grids WHERE grid_key = ?)
            """)) {
            for (StableKey key : changed) {
                delete.setString(1, key.toString());
                delete.addBatch();
            }
            if (!changed.isEmpty()) delete.executeBatch();
        }
        Set<StableKey> written = new HashSet<>(changed);
        written.addAll(dirty);
        for (StableKey key : written) {
            GridPersistentData data = next.get(key);
            if (data == null) {
                // Retirement removes membership, but the row keeps the last selected history/settings policy.
                if (dirty.contains(key)) saveSettings(
                    db,
                    key,
                    previous.get(key)
                        .getSettings());
                continue;
            }
            long id = saveSettings(db, key, data.getSettings());
            if (!changed.contains(key)) continue;
            try (PreparedStatement insert = db.prepareStatement("""
                INSERT INTO grid_controllers (grid_id, dimension_id, x, y, z)
                VALUES (?, ?, ?, ?, ?)
                """)) {
                for (DimensionalCoords position : data.getControllers()) {
                    insert.setLong(1, id);
                    insert.setString(2, position.dimensionId());
                    insert.setInt(3, position.x());
                    insert.setInt(4, position.y());
                    insert.setInt(5, position.z());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private static long saveSettings(Connection db, StableKey key, GridSettingsData settings) throws SQLException {
        try (PreparedStatement update = db.prepareStatement("""
            MERGE INTO grids g
            USING (VALUES (CAST(? AS VARCHAR(22)))) incoming(grid_key)
            ON g.grid_key = incoming.grid_key
            WHEN NOT MATCHED THEN INSERT (
                grid_key, row_count, deleted_until, first_observed, retention_days,
                name, crafting_tracked, stock_tracked, flow_tracked, stock_seconds, flow_seconds
            )
            VALUES (incoming.grid_key, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            update.setString(1, key.toString());
            update.setLong(2, Long.MIN_VALUE);
            update.setLong(3, Long.MAX_VALUE);
            update.setInt(4, settings.getHistoryRetentionDays());
            update.setString(5, settings.getName());
            update.setBoolean(6, settings.isTracked());
            update.setBoolean(7, settings.isStorageTracked());
            update.setBoolean(8, settings.isFlowTracked());
            update.setInt(9, settings.getStockSampleIntervalSeconds());
            update.setInt(10, settings.getFlowFlushIntervalSeconds());
            update.executeUpdate();
        }
        try (PreparedStatement update = db.prepareStatement("""
            UPDATE grids
            SET name = ?, crafting_tracked = ?, stock_tracked = ?, flow_tracked = ?,
                stock_seconds = ?, flow_seconds = ?, retention_days = ?
            WHERE grid_key = ?
            """)) {
            update.setString(1, settings.getName());
            update.setBoolean(2, settings.isTracked());
            update.setBoolean(3, settings.isStorageTracked());
            update.setBoolean(4, settings.isFlowTracked());
            update.setInt(5, settings.getStockSampleIntervalSeconds());
            update.setInt(6, settings.getFlowFlushIntervalSeconds());
            update.setInt(7, settings.getHistoryRetentionDays());
            update.setString(8, key.toString());
            update.executeUpdate();
        }
        try (PreparedStatement query = db.prepareStatement("SELECT id FROM grids WHERE grid_key = ?")) {
            query.setString(1, key.toString());
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
