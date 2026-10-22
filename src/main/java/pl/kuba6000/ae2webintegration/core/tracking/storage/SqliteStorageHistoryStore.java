package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.ResourceHistory;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.ResourceCatalogue;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.persistence.WorldGridStore;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Disk-backed typed history. All JDBC work belongs to detached worker/request threads. */
public final class SqliteStorageHistoryStore implements IStorageHistoryStore {

    enum Table {

        STOCK("history_stock"),
        FLOW("history_flow"),
        COVERAGE("history_coverage");

        final @NotNull String sqlName;

        Table(@NotNull String sqlName) {
            this.sqlName = sqlName;
        }

        String joined() {
            return sqlName + " p JOIN history_measurements m ON m.id = p.measurement_id";
        }

        static @NotNull Table forKind(@NotNull StorageHistoryPoint.Kind kind) {
            return switch (kind) {
                case STOCK -> STOCK;
                case FLOW -> FLOW;
                default -> COVERAGE;
            };
        }
    }

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    private final WorldDatabase database;
    private final SqliteHistoryRetention retention;
    private volatile @Nullable ResourceHistory appliedPolicy;

    public SqliteStorageHistoryStore(@NotNull WorldDatabase database) {
        this.database = database;
        retention = new SqliteHistoryRetention();
    }

    /** Enforces persisted budgets before world services publish; standalone callers may initialize lazily. */
    public void initialize() throws IOException {
        if (appliedPolicy == database.policy()) return;
        appliedPolicy = database.write(this::applyPolicy);
        maintain();
    }

    /** Captured inside the writer gate, including when append waited after an earlier initialization. */
    private @NotNull ResourceHistory applyPolicy(@NotNull Connection db) throws SQLException {
        ResourceHistory current = database.policy();
        if (appliedPolicy != current) {
            WorldGridStore.normalizePolicy(db, current);
            retention.enforceExistingBudgets(db, current.historyMaxRowsPerGrid);
            retention.policyChanged();
        }
        return current;
    }

    @Override
    public void append(@NotNull StableKey gridKey, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<StorageHistoryPoint> points) throws IOException {
        initialize();
        appliedPolicy = database.write(db -> {
            ResourceHistory current = applyPolicy(db);
            long gridId = gridId(db, gridKey);
            if (gridId == -1) throw new IOException("History requires a durable grid identity");
            updateDescriptions(db, descriptions, points);
            insertPoints(db, gridId, points, resourceIds(db, points));
            retention.prune(db, gridId, false, current.historyMaxRowsPerGrid);
            return current;
        });
        // Optional expiry yields the fair gate after the atomic measurement transaction.
        maintain();
    }

    /** Applies pending policy changes and bounded expiry, including for idle worlds. Failures remain retryable. */
    public void maintain() {
        try {
            appliedPolicy = database.write(db -> {
                ResourceHistory current = applyPolicy(db);
                retention.maintain(db, current.historyMaxRowsPerGrid);
                return current;
            });
        } catch (IOException failure) {
            // Measurements already committed; optional cleanup failure must not mark those writes lost.
            LOG.warn("Optional history cleanup failed; a later maintenance opportunity will retry", failure);
        }
    }

    private static void insertPoints(@NotNull Connection db, long gridId, @NotNull List<StorageHistoryPoint> points,
        @NotNull Map<StableKey, Long> resourceIds) throws SQLException {
        long nextId = reserveEventIds(db, points.size());
        int inserted = 0;
        long firstObserved = Long.MAX_VALUE;
        try (PreparedStatement stock = db.prepareStatement("""
            INSERT INTO history_stock (id, measurement_id, resource_id, quantity)
            VALUES (?, ?, ?, ?)
            """);
            PreparedStatement flow = db.prepareStatement(
                """
                    INSERT INTO history_flow (id, measurement_id, resource_id, input_amount, output_amount, input_operations, output_operations)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
            PreparedStatement coverage = db.prepareStatement("""
                INSERT INTO history_coverage (id, measurement_id, kind)
                VALUES (?, ?, ?)
                """)) {
            PreparedStatement pending = null;
            int groupEnd = 0;
            long measurementId = -1;
            for (int index = 0; index < points.size(); index++) {
                StorageHistoryPoint point = points.get(index);
                if (point.kind() == StorageHistoryPoint.Kind.FLOW && point.flowFrom() == null)
                    throw new SQLException("Flow history requires an interval start");
                if (index == groupEnd) {
                    groupEnd = measurementEnd(points, index);
                    Long flowFrom = null;
                    for (int member = index; member < groupEnd; member++) {
                        StorageHistoryPoint candidate = points.get(member);
                        if (candidate.kind() == StorageHistoryPoint.Kind.FLOW
                            || candidate.kind() == StorageHistoryPoint.Kind.FLOW_OBSERVATION)
                            flowFrom = candidate.flowFrom();
                    }
                    measurementId = insertMeasurement(db, gridId, point, flowFrom);
                }
                Table table = Table.forKind(point.kind());
                PreparedStatement insert = switch (table) {
                    case STOCK -> stock;
                    case FLOW -> flow;
                    case COVERAGE -> coverage;
                };
                // Bound each pending batch; IDs are reserved together in the original cross-table append order.
                if (pending != null && pending != insert) {
                    pending.executeBatch();
                    pending.clearBatch();
                }
                insert.setLong(1, nextId++);
                insert.setLong(2, measurementId);
                if (table == Table.COVERAGE) {
                    insert.setString(
                        3,
                        point.kind()
                            .name());
                } else {
                    Long resourceId = resourceIds.get(point.itemKey());
                    if (resourceId == null) insert.setNull(3, Types.BIGINT);
                    else insert.setLong(3, resourceId);
                    insert.setLong(4, table == Table.STOCK ? point.quantity() : point.input());
                    if (table == Table.FLOW) {
                        insert.setLong(5, point.output());
                        insert.setLong(6, point.inputOperations());
                        insert.setLong(7, point.outputOperations());
                    }
                }
                insert.addBatch();
                pending = insert;
                inserted++;
                firstObserved = Math.min(firstObserved, point.observedAt());
            }
            if (pending != null) pending.executeBatch();
        }
        try (PreparedStatement update = db.prepareStatement("""
            UPDATE grids
            SET row_count = row_count + ?,
                first_observed = CASE WHEN first_observed > ? THEN ? ELSE first_observed END
            WHERE id = ?
            """)) {
            update.setInt(1, inserted);
            update.setLong(2, firstObserved);
            update.setLong(3, firstObserved);
            update.setLong(4, gridId);
            update.executeUpdate();
        }
    }

    /** The counter survives pruning and commits or rolls back with the events that reserve it. */
    private static long reserveEventIds(Connection db, int count) throws SQLException {
        if (count == 0) return 0;
        try (PreparedStatement update = db
            .prepareStatement("UPDATE history_event_ids SET last_id = last_id + ? WHERE singleton = 1")) {
            update.setInt(1, count);
            if (update.executeUpdate() != 1) throw new SQLException("Missing history event counter");
        }
        try (Statement query = db.createStatement();
            ResultSet rows = query.executeQuery("SELECT last_id FROM history_event_ids WHERE singleton = 1")) {
            if (!rows.next()) throw new SQLException("Missing history event counter");
            return rows.getLong(1) - count + 1;
        }
    }

    /** A heterogeneous append may contain several observations; gaps and incompatible flow windows split them. */
    private static int measurementEnd(List<StorageHistoryPoint> points, int start) {
        StorageHistoryPoint first = points.get(start);
        if (first.kind() == StorageHistoryPoint.Kind.GAP) return start + 1;
        Long flowFrom = null;
        boolean flowSeen = false;
        int end = start;
        while (end < points.size()) {
            StorageHistoryPoint candidate = points.get(end);
            if (candidate.observedAt() != first.observedAt() || candidate.generation() != first.generation()
                || candidate.kind() == StorageHistoryPoint.Kind.GAP) break;
            if (candidate.kind() == StorageHistoryPoint.Kind.FLOW
                || candidate.kind() == StorageHistoryPoint.Kind.FLOW_OBSERVATION) {
                if (flowSeen && !Objects.equals(flowFrom, candidate.flowFrom())) break;
                flowFrom = candidate.flowFrom();
                flowSeen = true;
            }
            end++;
        }
        return end;
    }

    private static long insertMeasurement(Connection db, long gridId, StorageHistoryPoint point, Long flowFrom)
        throws SQLException {
        try (PreparedStatement insert = db.prepareStatement("""
            INSERT INTO history_measurements (grid_id, observed_at, generation, flow_from) VALUES (?, ?, ?, ?)
            """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, gridId);
            insert.setLong(2, point.observedAt());
            insert.setLong(3, point.generation());
            insert.setObject(4, flowFrom, Types.BIGINT);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static @NotNull Map<StableKey, Long> resourceIds(@NotNull Connection db,
        @NotNull List<StorageHistoryPoint> points) throws SQLException {
        Set<StableKey> keys = new HashSet<>();
        for (StorageHistoryPoint point : points) {
            if (point.itemKey() != null) keys.add(point.itemKey());
        }
        List<StableKey> selected = new ArrayList<>(keys);
        Map<StableKey, Long> ids = new HashMap<>();
        for (int offset = 0; offset < selected.size(); offset += 500) {
            List<StableKey> batch = selected.subList(offset, Math.min(offset + 500, selected.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            try (PreparedStatement query = db.prepareStatement(
                "SELECT id, item_key FROM history_resources WHERE item_key IN (" + placeholders + ")")) {
                for (int index = 0; index < batch.size(); index++) query.setString(
                    index + 1,
                    batch.get(index)
                        .toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) ids.put(StableKey.parse(rows.getString("item_key")), rows.getLong("id"));
                }
            }
        }
        return ids;
    }

    private static long gridId(@NotNull Connection db, @NotNull StableKey key) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT id
            FROM grids
            WHERE grid_key = ?
            """)) {
            query.setString(1, key.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1;
            }
        }
    }

    /** Keep the last known base variant if a later native normalizer cannot supply it. */
    private static void updateDescriptions(@NotNull Connection db,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, @NotNull List<StorageHistoryPoint> points)
        throws SQLException {
        if (descriptions.isEmpty()) return;
        Set<StableKey> referenced = new HashSet<>();
        for (StorageHistoryPoint point : points) {
            if (point.itemKey() != null) referenced.add(point.itemKey());
        }
        ResourceCatalogue.upsert(db, descriptions, referenced);
    }

    @Override
    public @NotNull StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis,
        int limit) throws IOException {
        if (limit < 1 || limit > 10000)
            throw new IllegalArgumentException("History query limit must be between 1 and 10000");
        if (Thread.currentThread()
            .isInterrupted()) throw new IOException("Interrupted while waiting to read history");
        initialize();
        return database
            .read(db -> SqliteHistoryReader.read(db, gridKey, itemKey, fromMillis, System.currentTimeMillis(), limit));
    }

    /** Called only after registry admission stops and the history worker drains. */
    @Override
    public void close() throws IOException {
        database.close();
    }
}
