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

import com.github.bsideup.jabel.Desugar;

/** Retention and row budgets, executed inside the owning store transaction. */
final class HsqlHistoryRetention {

    // Retain the latest expired stock anchors and gap barrier; deleting the barrier would invent continuity.
    private static final String EXPIRED_POINTS = """
        p.observed_at < ?
        AND (
            p.kind NOT IN ('STOCK', 'GAP')
            OR p.kind = 'STOCK' AND p.item_key IS NULL
            OR EXISTS (
                SELECT 1
                FROM history_points newer
                WHERE newer.grid_id = p.grid_id
                    AND (newer.item_key = p.item_key OR p.kind = 'GAP')
                    AND newer.kind = p.kind
                    AND newer.observed_at < ?
                    AND (
                        newer.observed_at > p.observed_at
                        OR newer.observed_at = p.observed_at AND newer.id > p.id
                    )
            )
        )
        """;
    private static final int PRUNE_BATCH_SIZE = 1000;
    private static final long MAINTENANCE_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final int retentionDays;
    private final int maxRowsPerGrid;
    private long nextMaintenance;

    HsqlHistoryRetention(int retentionDays, int maxRowsPerGrid) {
        this.retentionDays = retentionDays;
        this.maxRowsPerGrid = maxRowsPerGrid;
    }

    long cutoff() {
        return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
    }

    void prune(@NotNull Connection db, long gridId, boolean expire) throws SQLException {
        long count = rowCount(db, gridId);
        long expiry = cutoff();
        while (true) {
            boolean overCapacity = count > maxRowsPerGrid;
            if (!overCapacity && !expire) return;
            int limit = (int) Math.min(PRUNE_BATCH_SIZE, overCapacity ? count - maxRowsPerGrid : PRUNE_BATCH_SIZE);
            PruneBatch batch = selectBatch(db, gridId, expiry, overCapacity, limit);
            if (batch.ids.isEmpty()) return;
            deleteBatch(db, batch);
            count -= batch.ids.size();
            updateCoverage(db, gridId, count, batch.deletedUntil);
            // Time retention progresses in bounded chunks; the row cap is enforced before commit.
            if (!overCapacity) return;
        }
    }

    private static long rowCount(@NotNull Connection db, long gridId) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT row_count
            FROM history_grids
            WHERE id = ?
            """)) {
            query.setLong(1, gridId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static @NotNull PruneBatch selectBatch(@NotNull Connection db, long gridId, long expiry,
        boolean overCapacity, int limit) throws SQLException {
        List<Long> ids = new ArrayList<>();
        Set<String> resources = new HashSet<>();
        long deletedUntil = Long.MIN_VALUE;
        String expiryFilter = overCapacity ? "" : "AND " + EXPIRED_POINTS;
        try (PreparedStatement query = db.prepareStatement("""
            SELECT p.id, p.item_key, p.observed_at
            FROM history_points p
            WHERE p.grid_id = ?
            """ + expiryFilter + "ORDER BY p.observed_at, p.id")) {
            query.setLong(1, gridId);
            if (!overCapacity) {
                query.setLong(2, expiry);
                query.setLong(3, expiry);
            }
            query.setMaxRows(limit);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong("id"));
                    String resource = rows.getString("item_key");
                    if (resource != null) resources.add(resource);
                    deletedUntil = Math.max(deletedUntil, rows.getLong("observed_at"));
                }
            }
        }
        return new PruneBatch(ids, resources, deletedUntil);
    }

    @Desugar
    private record PruneBatch(@NotNull List<Long> ids, @NotNull Set<String> resources, long deletedUntil) {}

    private static void deleteBatch(@NotNull Connection db, @NotNull PruneBatch batch) throws SQLException {
        try (PreparedStatement delete = db.prepareStatement("DELETE FROM history_points WHERE id = ?")) {
            for (long id : batch.ids) {
                delete.setLong(1, id);
                delete.addBatch();
            }
            delete.executeBatch();
        }
        try (PreparedStatement delete = db.prepareStatement("""
            DELETE FROM history_resources
            WHERE item_key = ?
                AND NOT EXISTS (
                    SELECT 1
                    FROM history_points
                    WHERE item_key = ?
                )
            """)) {
            for (String itemKey : batch.resources) {
                delete.setString(1, itemKey);
                delete.setString(2, itemKey);
                delete.addBatch();
            }
            if (!batch.resources.isEmpty()) delete.executeBatch();
        }
    }

    private static void updateCoverage(@NotNull Connection db, long gridId, long count, long deletedUntil)
        throws SQLException {
        try (PreparedStatement update = db.prepareStatement("""
            UPDATE history_grids
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
        try (PreparedStatement query = db.prepareStatement("""
            SELECT p.grid_id
            FROM history_points p
            WHERE
            """ + EXPIRED_POINTS + "ORDER BY p.observed_at, p.id")) {
            long expiry = cutoff();
            query.setLong(1, expiry);
            query.setLong(2, expiry);
            query.setMaxRows(1);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) expired = rows.getLong(1);
            }
        }
        if (expired != null) prune(db, expired, true);
    }

    void enforceExistingBudgets(@NotNull Connection db) throws SQLException {
        while (true) {
            Long overBudget = null;
            try (PreparedStatement query = db.prepareStatement("""
                SELECT id
                FROM history_grids
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
