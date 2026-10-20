package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.ResourceCatalogue;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Disk-backed typed history. All JDBC work belongs to detached worker/request threads. */
public final class HsqlStorageHistoryStore implements IStorageHistoryStore {

    enum Table {

        STOCK("history_stock"),
        FLOW("history_flow"),
        COVERAGE("history_coverage");

        final @NotNull String sqlName;

        Table(@NotNull String sqlName) {
            this.sqlName = sqlName;
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
    private final HsqlHistoryRetention retention;
    private volatile boolean initialized;

    public HsqlStorageHistoryStore(@NotNull WorldDatabase database) {
        this.database = database;
        retention = new HsqlHistoryRetention(
            database.policy()
                .historyMaxRowsPerGrid());
    }

    /** Enforces persisted budgets before world services publish; standalone callers may initialize lazily. */
    public void initialize() throws IOException {
        if (initialized) return;
        database.write(db -> {
            if (!initialized) {
                retention.enforceExistingBudgets(db);
            }
            return null;
        });
        initialized = true;
        maintain();
    }

    @Override
    public void append(@NotNull StableKey gridKey, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<StorageHistoryPoint> points) throws IOException {
        initialize();
        database.write(db -> {
            long gridId = gridId(db, gridKey);
            if (gridId == -1) throw new IOException("History requires a durable grid identity");
            updateDescriptions(db, descriptions, points);
            insertPoints(db, gridId, points, resourceIds(db, points));
            retention.prune(db, gridId, false);
            return null;
        });
        // Optional expiry yields the fair gate after the atomic measurement transaction.
        maintain();
    }

    private void maintain() {
        try {
            database.write(db -> {
                retention.maintain(db);
                return null;
            });
        } catch (IOException failure) {
            // Measurements already committed; optional cleanup failure must not mark those writes lost.
            LOG.warn("Optional history cleanup failed; a later maintenance opportunity will retry", failure);
        }
    }

    private static void insertPoints(@NotNull Connection db, long gridId, @NotNull List<StorageHistoryPoint> points,
        @NotNull Map<StableKey, Long> resourceIds) throws SQLException {
        int inserted = 0;
        long firstObserved = Long.MAX_VALUE;
        try (PreparedStatement stock = db.prepareStatement("""
            INSERT INTO history_stock (id, grid_id, observed_at, generation, resource_id, quantity)
            VALUES (NEXT VALUE FOR history_event_ids, ?, ?, ?, ?, ?)
            """);
            PreparedStatement flow = db.prepareStatement(
                """
                    INSERT INTO history_flow (id, grid_id, observed_at, generation, resource_id, input_amount, output_amount, flow_from)
                    VALUES (NEXT VALUE FOR history_event_ids, ?, ?, ?, ?, ?, ?, ?)
                    """);
            PreparedStatement coverage = db.prepareStatement("""
                INSERT INTO history_coverage (id, grid_id, observed_at, generation, kind, flow_from)
                VALUES (NEXT VALUE FOR history_event_ids, ?, ?, ?, ?, ?)
                """)) {
            PreparedStatement pending = null;
            for (StorageHistoryPoint point : points) {
                Table table = Table.forKind(point.kind());
                PreparedStatement insert = switch (table) {
                    case STOCK -> stock;
                    case FLOW -> flow;
                    case COVERAGE -> coverage;
                };
                // Flush each contiguous group so the shared sequence preserves cross-table append order.
                if (pending != null && pending != insert) {
                    pending.executeBatch();
                    pending.clearBatch();
                }
                insert.setLong(1, gridId);
                insert.setLong(2, point.observedAt());
                insert.setLong(3, point.generation());
                if (table == Table.COVERAGE) {
                    insert.setString(
                        4,
                        point.kind()
                            .name());
                    insert.setObject(5, point.flowFrom(), Types.BIGINT);
                } else {
                    Long resourceId = resourceIds.get(point.itemKey());
                    if (resourceId == null) insert.setNull(4, Types.BIGINT);
                    else insert.setLong(4, resourceId);
                    insert.setLong(5, table == Table.STOCK ? point.quantity() : point.input());
                    if (table == Table.FLOW) {
                        insert.setLong(6, point.output());
                        insert.setObject(7, point.flowFrom(), Types.BIGINT);
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
            .read(db -> HsqlHistoryReader.read(db, gridKey, itemKey, fromMillis, System.currentTimeMillis(), limit));
    }

    /** Called only after registry admission stops and the history worker drains. */
    @Override
    public void close() throws IOException {
        database.close();
    }
}
