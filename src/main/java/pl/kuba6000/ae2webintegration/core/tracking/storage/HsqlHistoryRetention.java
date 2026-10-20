package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.persistence.ResourceCatalogue;
import pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore.Table;

/** Retention and row budgets, executed inside the owning store transaction. */
final class HsqlHistoryRetention {

    // Retain the latest expired stock anchors and gap barrier; deleting the barrier would invent continuity.
    private static String expiredPoints(Table table, String cutoff) {
        if (table == Table.FLOW) return "m.observed_at < " + cutoff + "\n";
        String matching = table == Table.STOCK ? "newer.resource_id = p.resource_id" : "newer.kind = 'GAP'";
        String ordinary = table == Table.COVERAGE ? "p.kind <> 'GAP' OR " : "";
        return "m.observed_at < " + cutoff
            + " AND ("
            + ordinary
            + "EXISTS (SELECT 1 FROM "
            + table.sqlName
            + " newer JOIN history_measurements n ON n.id = newer.measurement_id\n"
            + """
                WHERE n.grid_id = m.grid_id AND
                """
            + matching
            + "\n"
            + """
                    AND n.observed_at < %s
                    AND (n.observed_at > m.observed_at OR n.observed_at = m.observed_at AND newer.id > p.id)
                ))
                """.replace("%s", cutoff);
    }

    private static final int PRUNE_BATCH_SIZE = 1000;
    private static final long MAINTENANCE_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final int maxRowsPerGrid;
    private long nextMaintenance;

    HsqlHistoryRetention(int maxRowsPerGrid) {
        this.maxRowsPerGrid = maxRowsPerGrid;
    }

    void prune(@NotNull Connection db, long gridId, boolean expire) throws SQLException {
        GridBudget budget = budget(db, gridId);
        long count = budget.count();
        long expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(budget.retentionDays());
        while (true) {
            boolean overCapacity = count > maxRowsPerGrid;
            if (!overCapacity && !expire) return;
            int limit = (int) Math.min(PRUNE_BATCH_SIZE, overCapacity ? count - maxRowsPerGrid : PRUNE_BATCH_SIZE);
            PruneBatch batch = selectBatch(db, gridId, expiry, overCapacity, limit);
            if (batch.points.isEmpty()) return;
            deleteBatch(db, batch);
            count -= batch.points.size();
            updateCoverage(db, gridId, count, batch.deletedUntil);
            // Time retention progresses in bounded chunks; the row cap is enforced before commit.
            if (!overCapacity) return;
        }
    }

    private static GridBudget budget(@NotNull Connection db, long gridId) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT row_count, retention_days
            FROM grids
            WHERE id = ?
            """)) {
            query.setLong(1, gridId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return new GridBudget(rows.getLong(1), rows.getInt(2));
            }
        }
    }

    @Desugar
    private record GridBudget(long count, int retentionDays) {}

    private static @NotNull PruneBatch selectBatch(@NotNull Connection db, long gridId, long expiry,
        boolean overCapacity, int limit) throws SQLException {
        List<PrunePoint> candidates = new ArrayList<>();
        Set<Long> resources = new HashSet<>();
        long deletedUntil = Long.MIN_VALUE;
        for (Table table : Table.values()) {
            String expiryFilter = overCapacity ? "" : "AND " + expiredPoints(table, "?");
            String resource = table == Table.COVERAGE ? "CAST(NULL AS BIGINT)" : "p.resource_id";
            try (PreparedStatement query = db.prepareStatement(
                "SELECT p.id, p.measurement_id, " + resource
                    + " AS resource_id, m.observed_at FROM "
                    + table.joined()
                    + "\n"
                    + """
                        WHERE m.grid_id = ?
                        """
                    + expiryFilter
                    + "ORDER BY m.observed_at, p.id")) {
                query.setLong(1, gridId);
                if (!overCapacity) {
                    query.setLong(2, expiry);
                    if (table != Table.FLOW) query.setLong(3, expiry);
                }
                query.setMaxRows(limit);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        candidates.add(
                            new PrunePoint(
                                table,
                                rows.getLong("id"),
                                rows.getLong("measurement_id"),
                                rows.getLong("observed_at"),
                                (Long) rows.getObject("resource_id")));
                    }
                }
            }
        }
        candidates.sort((left, right) -> {
            int time = Long.compare(left.observedAt, right.observedAt);
            return time == 0 ? Long.compare(left.id, right.id) : time;
        });
        List<PrunePoint> selected = new ArrayList<>(candidates.subList(0, Math.min(limit, candidates.size())));
        for (PrunePoint point : selected) {
            if (point.resourceId != null) resources.add(point.resourceId);
            deletedUntil = Math.max(deletedUntil, point.observedAt);
        }
        return new PruneBatch(selected, resources, deletedUntil);
    }

    @Desugar
    private record PrunePoint(@NotNull Table table, long id, long measurementId, long observedAt,
        @Nullable Long resourceId) {}

    @Desugar
    private record PruneBatch(@NotNull List<PrunePoint> points, @NotNull Set<Long> resources, long deletedUntil) {}

    private static void deleteBatch(@NotNull Connection db, @NotNull PruneBatch batch) throws SQLException {
        for (Table table : Table.values()) {
            try (PreparedStatement delete = db.prepareStatement("DELETE FROM " + table.sqlName + " WHERE id = ?")) {
                boolean pending = false;
                for (PrunePoint point : batch.points) {
                    if (point.table != table) continue;
                    delete.setLong(1, point.id);
                    delete.addBatch();
                    pending = true;
                }
                if (pending) delete.executeBatch();
            }
        }
        Set<Long> measurements = new HashSet<>();
        for (PrunePoint point : batch.points) measurements.add(point.measurementId);
        try (PreparedStatement delete = db.prepareStatement("""
            DELETE FROM history_measurements m WHERE id = ?
                AND NOT EXISTS (SELECT 1 FROM history_stock WHERE measurement_id = m.id)
                AND NOT EXISTS (SELECT 1 FROM history_flow WHERE measurement_id = m.id)
                AND NOT EXISTS (SELECT 1 FROM history_coverage WHERE measurement_id = m.id)
            """)) {
            for (Long measurement : measurements) {
                delete.setLong(1, measurement);
                delete.addBatch();
            }
            delete.executeBatch();
        }
        ResourceCatalogue.removeUnreferenced(db, batch.resources);
    }

    private static void updateCoverage(@NotNull Connection db, long gridId, long count, long deletedUntil)
        throws SQLException {
        try (PreparedStatement update = db.prepareStatement("""
            UPDATE grids
            SET row_count = ?,
                deleted_until = CASE WHEN deleted_until < ? THEN ? ELSE deleted_until END
            WHERE id = ?
            """)) {
            update.setLong(1, count);
            update.setLong(2, deletedUntil);
            update.setLong(3, deletedUntil);
            update.setLong(4, gridId);
            update.executeUpdate();
        }
    }

    void maintain(@NotNull Connection db) throws SQLException {
        long now = System.currentTimeMillis();
        if (now < nextMaintenance) return;
        nextMaintenance = now + MAINTENANCE_INTERVAL_MILLIS;
        Long expired = null;
        long earliestTime = Long.MAX_VALUE;
        long earliestId = Long.MAX_VALUE;
        // Metadata is small; each history probe keeps a constant grid/time index range.
        try (PreparedStatement grids = db.prepareStatement("SELECT id, retention_days FROM grids WHERE row_count > 0");
            ResultSet policies = grids.executeQuery()) {
            while (policies.next()) {
                long gridId = policies.getLong("id");
                long expiry = now - TimeUnit.DAYS.toMillis(policies.getInt("retention_days"));
                for (Table table : Table.values()) {
                    try (PreparedStatement query = db.prepareStatement(
                        "SELECT m.observed_at, p.id FROM " + table.joined()
                            + "\n"
                            + "WHERE m.grid_id = ? AND "
                            + expiredPoints(table, "?")
                            + "ORDER BY m.observed_at, p.id")) {
                        query.setLong(1, gridId);
                        query.setLong(2, expiry);
                        if (table != Table.FLOW) query.setLong(3, expiry);
                        query.setMaxRows(1);
                        try (ResultSet rows = query.executeQuery()) {
                            if (rows.next()) {
                                long time = rows.getLong("observed_at");
                                long id = rows.getLong("id");
                                if (time < earliestTime || time == earliestTime && id < earliestId) {
                                    expired = gridId;
                                    earliestTime = time;
                                    earliestId = id;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (expired != null) prune(db, expired, true);
    }

    void enforceExistingBudgets(@NotNull Connection db) throws SQLException {
        while (true) {
            Long overBudget = null;
            try (PreparedStatement query = db.prepareStatement("""
                SELECT id
                FROM grids
                WHERE row_count > ?
                """)) {
                query.setInt(1, maxRowsPerGrid);
                query.setMaxRows(1);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) overBudget = rows.getLong(1);
                }
            }
            if (overBudget == null) return;
            prune(db, overBudget, false);
        }
    }
}
