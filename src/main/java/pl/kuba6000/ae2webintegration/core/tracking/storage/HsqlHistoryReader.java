package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore.Table;

/** Bounded history queries; connection and transaction belong to the store. */
final class HsqlHistoryReader {

    private static final int DESCRIPTION_QUERY_BATCH_SIZE = 500;

    private HsqlHistoryReader() {}

    static @NotNull StorageHistory read(@NotNull Connection db, @NotNull StableKey gridKey, @Nullable StableKey itemKey,
        long fromMillis, long nowMillis, int limit) throws SQLException {
        long gridId;
        boolean retired;
        long expiry;
        try (PreparedStatement query = db.prepareStatement("""
            SELECT id, deleted_until, first_observed, retention_days
            FROM grids
            WHERE grid_key = ?
            """)) {
            query.setString(1, gridKey.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next())
                    return new StorageHistory(Collections.emptyList(), Collections.emptyMap(), true, false);
                gridId = rows.getLong("id");
                long deletedUntil = rows.getLong("deleted_until");
                long firstObserved = rows.getLong("first_observed");
                expiry = nowMillis - TimeUnit.DAYS.toMillis(rows.getInt("retention_days"));
                retired = deletedUntil != Long.MIN_VALUE && fromMillis <= deletedUntil
                    || fromMillis < expiry && firstObserved < expiry;
            }
        }
        long start = Math.max(fromMillis, expiry);
        boolean complete = !retired && !hasGap(db, gridId, start);
        Long resourceId = itemKey == null ? null : resourceId(db, itemKey);
        List<QueryPoint> selected = selectPoints(db, gridId, resourceId, start, limit);
        boolean truncated = retired || selected.size() > limit;
        List<StorageHistoryPoint> points = new ArrayList<>();
        int count = Math.min(limit, selected.size());
        for (int index = 0; index < count; index++) points.add(selected.get(index).point);
        Collections.reverse(points);
        StorageHistoryPoint baseline = findBaseline(db, gridId, resourceId, start, selected.subList(0, count));
        includeBoundaryGap(db, gridId, points);
        Map<StableKey, ResourceDescriptor> descriptions = readDescriptions(db, points, baseline);
        return new StorageHistory(points, descriptions, complete, truncated, baseline);
    }

    /** A result limit must not detach partial measurements from their same-time coverage barrier. */
    private static void includeBoundaryGap(Connection db, long gridId, List<StorageHistoryPoint> points)
        throws SQLException {
        if (points.isEmpty()) return;
        long boundary = points.get(0)
            .observedAt();
        if (points.stream()
            .anyMatch(point -> point.observedAt() == boundary && point.kind() == StorageHistoryPoint.Kind.GAP)) return;
        try (PreparedStatement query = db.prepareStatement("""
            SELECT p.*, m.observed_at, m.generation, m.flow_from
            FROM history_coverage p JOIN history_measurements m ON m.id = p.measurement_id
            WHERE m.grid_id = ? AND m.observed_at = ? AND p.kind = 'GAP'
            ORDER BY p.id DESC
            """)) {
            query.setLong(1, gridId);
            query.setLong(2, boundary);
            query.setMaxRows(1);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) points.add(0, readPoint(rows, Table.COVERAGE));
            }
        }
    }

    private static @NotNull List<QueryPoint> selectPoints(@NotNull Connection db, long gridId,
        @Nullable Long resourceId, long fromMillis, int limit) throws SQLException {
        List<QueryPoint> selected = new ArrayList<>();
        for (Table table : Table.values())
            selected.addAll(queryPoints(db, gridId, resourceId, table, fromMillis, limit));
        selected.sort((left, right) -> {
            int time = Long.compare(right.point.observedAt(), left.point.observedAt());
            return time == 0 ? Long.compare(right.id, left.id) : time;
        });
        return selected;
    }

    private static @Nullable StorageHistoryPoint findBaseline(@NotNull Connection db, long gridId,
        @Nullable Long resourceId, long fromMillis, @NotNull List<QueryPoint> selected) throws SQLException {
        if (resourceId == null) return null;
        long boundary = selected.isEmpty() ? fromMillis : selected.get(selected.size() - 1).point.observedAt();
        QueryPoint anchor = queryBaseline(db, gridId, resourceId, boundary);
        if (anchor == null || crossesGap(db, gridId, anchor, boundary)) return null;
        for (QueryPoint point : selected) {
            if (point.id == anchor.id) return null;
        }
        return anchor.point;
    }

    private static @NotNull List<QueryPoint> queryPoints(@NotNull Connection db, long gridId, @Nullable Long resourceId,
        Table table, long fromMillis, int limit) throws SQLException {
        List<QueryPoint> result = new ArrayList<>();
        boolean filtered = resourceId != null && table != Table.COVERAGE;
        String filter = filtered ? "AND p.resource_id = ?\n" : "";
        try (PreparedStatement query = db.prepareStatement(
            "SELECT p.*, m.observed_at, m.generation, m.flow_from"
                + (table == Table.COVERAGE ? " FROM " + table.joined() + "\n"
                    : ", r.item_key FROM " + table.joined() + " JOIN history_resources r ON r.id = p.resource_id\n")
                + """
                    WHERE m.grid_id = ?
                        AND m.observed_at >= ?
                    """
                + filter
                + "ORDER BY m.observed_at DESC, p.id DESC")) {
            query.setLong(1, gridId);
            query.setLong(2, fromMillis);
            if (filtered) query.setLong(3, resourceId);
            query.setMaxRows(limit + 1);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    StorageHistoryPoint point = readPoint(rows, table);
                    result.add(new QueryPoint(rows.getLong("id"), point));
                }
            }
        }
        return result;
    }

    private static long resourceId(@NotNull Connection db, @NotNull StableKey key) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("SELECT id FROM history_resources WHERE item_key = ?")) {
            query.setString(1, key.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1;
            }
        }
    }

    private static boolean hasGap(@NotNull Connection db, long gridId, long fromMillis) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT p.id
            FROM history_coverage p JOIN history_measurements m ON m.id = p.measurement_id
            WHERE m.grid_id = ?
                AND p.kind = 'GAP'
                AND m.observed_at >= ?
            """)) {
            query.setLong(1, gridId);
            query.setLong(2, fromMillis);
            query.setMaxRows(1);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static @Nullable QueryPoint queryBaseline(@NotNull Connection db, long gridId, long resourceId,
        long boundary) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT p.*, m.observed_at, m.generation, m.flow_from, r.item_key
            FROM history_stock p JOIN history_measurements m ON m.id = p.measurement_id
                JOIN history_resources r ON r.id = p.resource_id
            WHERE m.grid_id = ?
                AND p.resource_id = ?
                AND m.observed_at <= ?
            ORDER BY m.observed_at DESC, p.id DESC
            """)) {
            query.setLong(1, gridId);
            query.setLong(2, resourceId);
            query.setLong(3, boundary);
            query.setMaxRows(1);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                return new QueryPoint(rows.getLong("id"), readPoint(rows, Table.STOCK));
            }
        }
    }

    private static boolean crossesGap(Connection db, long gridId, QueryPoint anchor, long boundary)
        throws SQLException {
        for (Table table : Table.values()) {
            String barrier = table == Table.COVERAGE ? "(p.kind = 'GAP' OR m.generation <> ?)" : "m.generation <> ?";
            try (PreparedStatement query = db.prepareStatement("SELECT p.id FROM " + table.joined() + "\n" + """
                WHERE m.grid_id = ?
                    AND (m.observed_at > ? OR m.observed_at = ? AND p.id > ?)
                    AND m.observed_at <= ?
                    AND
                """ + barrier)) {
                query.setLong(1, gridId);
                query.setLong(2, anchor.point.observedAt());
                query.setLong(3, anchor.point.observedAt());
                query.setLong(4, anchor.id);
                query.setLong(5, boundary);
                query.setLong(6, anchor.point.generation());
                query.setMaxRows(1);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) return true;
                }
            }
        }
        return false;
    }

    @Desugar
    private record QueryPoint(long id, @NotNull StorageHistoryPoint point) {}

    private static @NotNull StorageHistoryPoint readPoint(@NotNull ResultSet row, Table table) throws SQLException {
        String itemKey = table == Table.COVERAGE ? null : row.getString("item_key");
        StorageHistoryPoint.Kind kind = switch (table) {
            case STOCK -> StorageHistoryPoint.Kind.STOCK;
            case FLOW -> StorageHistoryPoint.Kind.FLOW;
            case COVERAGE -> StorageHistoryPoint.Kind.valueOf(row.getString("kind"));
        };
        return new StorageHistoryPoint(
            row.getLong("observed_at"),
            row.getLong("generation"),
            kind,
            itemKey == null ? null : StableKey.parse(itemKey),
            table == Table.STOCK ? row.getLong("quantity") : 0,
            table == Table.FLOW ? row.getLong("input_amount") : 0,
            table == Table.FLOW ? row.getLong("output_amount") : 0,
            kind == StorageHistoryPoint.Kind.FLOW || kind == StorageHistoryPoint.Kind.FLOW_OBSERVATION
                ? row.getLong("flow_from")
                : null);
    }

    private static @NotNull Map<StableKey, ResourceDescriptor> readDescriptions(@NotNull Connection db,
        @NotNull List<StorageHistoryPoint> points, @Nullable StorageHistoryPoint baseline) throws SQLException {
        Set<StableKey> keys = new HashSet<>();
        for (StorageHistoryPoint point : points) {
            if (point.itemKey() != null) keys.add(point.itemKey());
        }
        if (baseline != null && baseline.itemKey() != null) keys.add(baseline.itemKey());
        List<StableKey> selected = new ArrayList<>(keys);
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        for (int offset = 0; offset < selected.size(); offset += DESCRIPTION_QUERY_BATCH_SIZE) {
            List<StableKey> batch = selected
                .subList(offset, Math.min(offset + DESCRIPTION_QUERY_BATCH_SIZE, selected.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            try (PreparedStatement query = db.prepareStatement("""
                SELECT *
                FROM history_resources
                WHERE item_key IN (
                """ + placeholders + ")")) {
                for (int index = 0; index < batch.size(); index++) query.setString(
                    index + 1,
                    batch.get(index)
                        .toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        ResourceDescriptor description = readDescription(rows);
                        descriptions.put(description.itemKey(), description);
                    }
                }
            }
        }
        return descriptions;
    }

    private static @NotNull ResourceDescriptor readDescription(@NotNull ResultSet row) throws SQLException {
        String baseKey = row.getString("base_key");
        return new ResourceDescriptor(
            row.getString("registry_namespace"),
            row.getString("registry_path"),
            row.getInt("components"),
            row.getInt("damage"),
            row.getString("display_name"),
            ResourceType.valueOf(row.getString("resource_type")),
            StableKey.parse(row.getString("item_key")),
            baseKey == null ? null : StableKey.parse(baseKey));
    }

}
