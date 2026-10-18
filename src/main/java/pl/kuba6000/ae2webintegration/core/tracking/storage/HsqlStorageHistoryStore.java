package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.hsqldb.jdbc.JDBCDriver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
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

    private static final Set<Path> OPEN_DATABASES = ConcurrentHashMap.newKeySet();

    private static final int MAX_READERS = 4;

    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final Object writerLock = new Object();
    private final Semaphore readers = new Semaphore(MAX_READERS, true);
    private final Path databaseBase;
    private final HsqlHistoryRetention retention;
    private volatile @Nullable Database openedDatabase;
    private @Nullable Path ownedPath;
    private boolean closed;

    public HsqlStorageHistoryStore(@NotNull Path databaseBase, int retentionDays, int maxRowsPerGrid) {
        if (retentionDays < 1 || maxRowsPerGrid < 1)
            throw new IllegalArgumentException("History limits must be positive");
        if (databaseBase.toString()
            .indexOf(';') >= 0)
            throw new IllegalArgumentException("History path cannot contain JDBC property separators");
        this.databaseBase = databaseBase.toAbsolutePath();
        this.retention = new HsqlHistoryRetention(retentionDays, maxRowsPerGrid);
    }

    @Desugar
    private record Database(@NotNull Connection writer, @NotNull String url) {}

    private @NotNull Database database() throws SQLException, IOException {
        if (closed) throw new IOException("History store is closed");
        Database existing = openedDatabase;
        if (existing != null) return existing;
        Files.createDirectories(databaseBase.getParent());
        Path canonical = databaseBase.getParent()
            .toRealPath()
            .resolve(databaseBase.getFileName());
        if (!OPEN_DATABASES.add(canonical))
            throw new IOException("Storage history database is still owned by an active service");
        ownedPath = canonical;
        String url = "jdbc:hsqldb:file:" + canonical.toString()
            .replace('\\', '/');
        Connection opened;
        try {
            opened = connect(url);
        } catch (SQLException failedConnection) {
            releaseOwnership();
            throw failedConnection;
        }
        try {
            initializeSchema(opened);
            opened.setAutoCommit(false);
            retention.enforceExistingBudgets(opened);
            retention.maintain(opened);
            opened.commit();
            Database initialized = new Database(opened, url);
            openedDatabase = initialized;
            return initialized;
        } catch (SQLException failure) {
            try {
                opened.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            try {
                shutdown(opened);
            } catch (SQLException failedShutdown) {
                failure.addSuppressed(failedShutdown);
            }
            throw failure;
        }
    }

    private static @NotNull Connection connect(@NotNull String url) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "SA");
        properties.setProperty("password", "");
        return new JDBCDriver().connect(url, properties);
    }

    private static void initializeSchema(@NotNull Connection db) throws SQLException {
        try (Statement sql = db.createStatement()) {
            sql.execute("SET DATABASE TRANSACTION CONTROL MVCC");
            sql.execute("SET FILES WRITE DELAY FALSE");
            sql.execute("SET FILES CACHE SIZE 16384");
            sql.execute("SET FILES LOG SIZE 16");
            sql.execute("SET FILES DEFRAG 25");
            sql.execute("""
                CREATE CACHED TABLE IF NOT EXISTS history_grids (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    grid_key VARCHAR(22) NOT NULL UNIQUE,
                    row_count BIGINT NOT NULL,
                    deleted_until BIGINT NOT NULL,
                    first_observed BIGINT NOT NULL
                )
                """);
            sql.execute("""
                CREATE CACHED TABLE IF NOT EXISTS history_resources (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    item_key VARCHAR(22) NOT NULL UNIQUE,
                    registry_namespace VARCHAR(4096),
                    registry_path VARCHAR(16384),
                    components INTEGER NOT NULL,
                    damage INTEGER NOT NULL,
                    display_name VARCHAR(1048576) NOT NULL,
                    resource_type VARCHAR(16) NOT NULL,
                    base_key VARCHAR(22)
                )
                """);
            sql.execute("CREATE SEQUENCE IF NOT EXISTS history_event_ids AS BIGINT START WITH 1");
            sql.execute("""
                CREATE CACHED TABLE IF NOT EXISTS history_stock (
                    id BIGINT PRIMARY KEY,
                    grid_id BIGINT NOT NULL REFERENCES history_grids(id),
                    observed_at BIGINT NOT NULL,
                    generation BIGINT NOT NULL,
                    resource_id BIGINT NOT NULL REFERENCES history_resources(id),
                    quantity BIGINT NOT NULL
                )
                """);
            sql.execute("""
                CREATE CACHED TABLE IF NOT EXISTS history_flow (
                    id BIGINT PRIMARY KEY,
                    grid_id BIGINT NOT NULL REFERENCES history_grids(id),
                    observed_at BIGINT NOT NULL,
                    generation BIGINT NOT NULL,
                    resource_id BIGINT NOT NULL REFERENCES history_resources(id),
                    input_amount BIGINT NOT NULL,
                    output_amount BIGINT NOT NULL
                )
                """);
            sql.execute("""
                CREATE CACHED TABLE IF NOT EXISTS history_coverage (
                    id BIGINT PRIMARY KEY,
                    grid_id BIGINT NOT NULL REFERENCES history_grids(id),
                    observed_at BIGINT NOT NULL,
                    generation BIGINT NOT NULL,
                    kind VARCHAR(16) NOT NULL CHECK (kind IN ('GAP', 'OBSERVATION', 'FLOW_OBSERVATION'))
                )
                """);
            for (Table table : Table.values()) {
                String name = table.sqlName;
                sql.execute(
                    "CREATE INDEX IF NOT EXISTS " + name + "_grid_time ON " + name + "(grid_id, observed_at, id)");
                sql.execute("CREATE INDEX IF NOT EXISTS " + name + "_expiry ON " + name + "(observed_at, id)");
                if (table != Table.COVERAGE) {
                    sql.execute(
                        "CREATE INDEX IF NOT EXISTS " + name
                            + "_item_time ON "
                            + name
                            + "(grid_id, resource_id, observed_at, id)");
                    sql.execute("CREATE INDEX IF NOT EXISTS " + name + "_resource ON " + name + "(resource_id)");
                }
            }
            sql.execute(
                "CREATE INDEX IF NOT EXISTS history_coverage_gap ON history_coverage(grid_id, kind, observed_at, id)");
        }
    }

    @Override
    public void append(@NotNull StableKey gridKey, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<StorageHistoryPoint> points) throws IOException {
        lifecycle.readLock()
            .lock();
        try {
            synchronized (writerLock) {
                try {
                    Connection db = database().writer();
                    updateDescriptions(db, descriptions, points);
                    long gridId = ensureGrid(db, gridKey);
                    insertPoints(db, gridId, points, resourceIds(db, points));
                    retention.prune(db, gridId, false);
                    retention.maintain(db);
                    db.commit();
                } catch (SQLException failure) {
                    rollback(failure);
                    throw new IOException("Cannot append storage history", failure);
                }
            }
        } finally {
            lifecycle.readLock()
                .unlock();
        }
    }

    private static void insertPoints(@NotNull Connection db, long gridId, @NotNull List<StorageHistoryPoint> points,
        @NotNull Map<StableKey, Long> resourceIds) throws SQLException {
        int inserted = 0;
        long firstObserved = Long.MAX_VALUE;
        try (PreparedStatement stock = db.prepareStatement("""
            INSERT INTO history_stock (id, grid_id, observed_at, generation, resource_id, quantity)
            VALUES (NEXT VALUE FOR history_event_ids, ?, ?, ?, ?, ?)
            """); PreparedStatement flow = db.prepareStatement("""
            INSERT INTO history_flow (id, grid_id, observed_at, generation, resource_id, input_amount, output_amount)
            VALUES (NEXT VALUE FOR history_event_ids, ?, ?, ?, ?, ?, ?)
            """); PreparedStatement coverage = db.prepareStatement("""
            INSERT INTO history_coverage (id, grid_id, observed_at, generation, kind)
            VALUES (NEXT VALUE FOR history_event_ids, ?, ?, ?, ?)
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
                if (table == Table.COVERAGE) insert.setString(
                    4,
                    point.kind()
                        .name());
                else {
                    Long resourceId = resourceIds.get(point.itemKey());
                    if (resourceId == null) insert.setNull(4, Types.BIGINT);
                    else insert.setLong(4, resourceId);
                    insert.setLong(5, table == Table.STOCK ? point.quantity() : point.input());
                    if (table == Table.FLOW) insert.setLong(6, point.output());
                }
                insert.addBatch();
                pending = insert;
                inserted++;
                firstObserved = Math.min(firstObserved, point.observedAt());
            }
            if (pending != null) pending.executeBatch();
        }
        try (PreparedStatement update = db.prepareStatement("""
            UPDATE history_grids
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

    private static long ensureGrid(@NotNull Connection db, @NotNull StableKey gridKey) throws SQLException {
        long existing = gridId(db, gridKey);
        if (existing != -1) return existing;
        try (PreparedStatement insert = db.prepareStatement("""
            INSERT INTO history_grids (grid_key, row_count, deleted_until, first_observed)
            VALUES (?, 0, ?, ?)
            """)) {
            insert.setString(1, gridKey.toString());
            insert.setLong(2, Long.MIN_VALUE);
            insert.setLong(3, Long.MAX_VALUE);
            insert.executeUpdate();
        }
        return gridId(db, gridKey);
    }

    private static long gridId(@NotNull Connection db, @NotNull StableKey key) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT id
            FROM history_grids
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
        try (PreparedStatement upsert = db.prepareStatement("""
            MERGE INTO history_resources r
            USING (VALUES (
                CAST(? AS VARCHAR(22)),
                CAST(? AS VARCHAR(4096)),
                CAST(? AS VARCHAR(16384)),
                CAST(? AS INTEGER),
                CAST(? AS INTEGER),
                CAST(? AS VARCHAR(1048576)),
                CAST(? AS VARCHAR(16)),
                CAST(? AS VARCHAR(22)),
                CAST(? AS BOOLEAN)
            )) AS incoming (
                item_key, registry_namespace, registry_path, components, damage,
                display_name, resource_type, base_key, referenced
            )
            ON r.item_key = incoming.item_key
            WHEN MATCHED THEN UPDATE SET
                registry_namespace = incoming.registry_namespace,
                registry_path = incoming.registry_path,
                components = incoming.components,
                damage = incoming.damage,
                display_name = incoming.display_name,
                resource_type = incoming.resource_type,
                base_key = COALESCE(incoming.base_key, r.base_key)
            WHEN NOT MATCHED AND incoming.referenced THEN INSERT (
                item_key, registry_namespace, registry_path, components, damage,
                display_name, resource_type, base_key
            ) VALUES (
                incoming.item_key, incoming.registry_namespace, incoming.registry_path,
                incoming.components, incoming.damage, incoming.display_name,
                incoming.resource_type, incoming.base_key
            )
            """)) {
            for (Map.Entry<StableKey, ResourceDescriptor> entry : descriptions.entrySet()) {
                ResourceDescriptor description = entry.getValue();
                upsert.setString(
                    1,
                    entry.getKey()
                        .toString());
                upsert.setString(2, description.registryNamespace());
                upsert.setString(3, description.registryPath());
                upsert.setInt(4, description.componentCount());
                upsert.setInt(5, description.damage());
                upsert.setString(6, description.displayName());
                upsert.setString(
                    7,
                    description.resourceType()
                        .name());
                upsert.setString(
                    8,
                    description.baseKey() == null ? null
                        : description.baseKey()
                            .toString());
                upsert.setBoolean(9, referenced.contains(entry.getKey()));
                upsert.addBatch();
            }
            upsert.executeBatch();
        }
    }

    @Override
    public @NotNull StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis,
        int limit) throws IOException {
        if (limit < 1 || limit > 10000)
            throw new IllegalArgumentException("History query limit must be between 1 and 10000");
        try {
            readers.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new IOException("Interrupted while waiting to read storage history", interrupted);
        }
        lifecycle.readLock()
            .lock();
        try {
            if (closed) throw new IOException("History store is closed");
            Database initialized = openedDatabase;
            if (initialized == null) {
                synchronized (writerLock) {
                    initialized = database();
                }
            }
            try (Connection db = connect(initialized.url())) {
                db.setReadOnly(true);
                // MVCC repeatable-read keeps all statements of one response on the same committed snapshot.
                db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                db.setAutoCommit(false);
                StorageHistory history = HsqlHistoryReader
                    .read(db, gridKey, itemKey, fromMillis, retention.cutoff(), limit);
                db.commit();
                return history;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot query storage history", failure);
        } finally {
            lifecycle.readLock()
                .unlock();
            readers.release();
        }
    }

    private void rollback(SQLException failure) {
        try {
            Database initialized = openedDatabase;
            if (initialized != null) initialized.writer()
                .rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @Override
    public void close() throws IOException {
        lifecycle.writeLock()
            .lock();
        try {
            if (closed) return;
            closed = true;
            Database initialized = openedDatabase;
            if (initialized == null) return;
            try {
                shutdown(initialized.writer());
            } catch (SQLException failure) {
                throw new IOException("Cannot close storage history", failure);
            } finally {
                openedDatabase = null;
            }
        } finally {
            lifecycle.writeLock()
                .unlock();
        }
    }

    private void shutdown(@NotNull Connection db) throws SQLException {
        SQLException failure = null;
        boolean stopped = false;
        try (Statement sql = db.createStatement()) {
            sql.execute("SHUTDOWN");
            stopped = true;
        } catch (SQLException failedShutdown) {
            failure = failedShutdown;
        }
        try {
            db.close();
        } catch (SQLException failedClose) {
            if (failure == null) failure = failedClose;
            else failure.addSuppressed(failedClose);
        }
        // A failed SHUTDOWN keeps the path reserved until process exit: another owner must not share that engine.
        if (stopped) releaseOwnership();
        if (failure != null) throw failure;
    }

    private void releaseOwnership() {
        if (ownedPath == null) return;
        OPEN_DATABASES.remove(ownedPath);
        ownedPath = null;
    }
}
