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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Bounded history queries; connection and transaction belong to the store. */
final class HsqlHistoryReader {

    private static final int DESCRIPTION_QUERY_BATCH_SIZE = 500;

    private HsqlHistoryReader() {}

    static @NotNull StorageHistory read(@NotNull Connection db, @NotNull StableKey gridKey, @Nullable StableKey itemKey,
        long fromMillis, long expiry, int limit) throws SQLException {
        long gridId;
        boolean retired;
        try (PreparedStatement query = db.prepareStatement("""
            SELECT id, deleted_until, first_observed
            FROM history_grids
            WHERE grid_key = ?
            """)) {
            query.setString(1, gridKey.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next())
                    return new StorageHistory(Collections.emptyList(), Collections.emptyMap(), true, false);
                gridId = rows.getLong("id");
                long deletedUntil = rows.getLong("deleted_until");
                long firstObserved = rows.getLong("first_observed");
                retired = deletedUntil != Long.MIN_VALUE && fromMillis <= deletedUntil
                    || fromMillis < expiry && firstObserved < expiry;
            }
        }
        long start = Math.max(fromMillis, expiry);
        boolean complete = !retired && !hasIncomplete(db, gridId, itemKey, start);
        List<QueryPoint> selected = selectPoints(db, gridId, itemKey, start, limit);
        boolean truncated = retired || selected.size() > limit;
        List<StorageHistoryPoint> points = new ArrayList<>();
        int count = Math.min(limit, selected.size());
        for (int index = 0; index < count; index++) points.add(selected.get(index).point);
        Collections.reverse(points);
        StorageHistoryPoint baseline = findBaseline(db, gridId, itemKey, start, selected.subList(0, count));
        Map<StableKey, ResourceDescriptor> descriptions = readDescriptions(db, points, baseline);
        return new StorageHistory(points, descriptions, complete, truncated, baseline);
    }

    private static @NotNull List<QueryPoint> selectPoints(@NotNull Connection db, long gridId,
        @Nullable StableKey itemKey, long fromMillis, int limit) throws SQLException {
        List<QueryPoint> selected = queryPoints(db, gridId, itemKey, false, fromMillis, limit);
        if (itemKey != null) {
            selected.addAll(queryPoints(db, gridId, null, true, fromMillis, limit));
            selected.sort((left, right) -> {
                int time = Long.compare(right.point.observedAt(), left.point.observedAt());
                return time == 0 ? Long.compare(right.id, left.id) : time;
            });
        }
        return selected;
    }

    private static @Nullable StorageHistoryPoint findBaseline(@NotNull Connection db, long gridId,
        @Nullable StableKey itemKey, long fromMillis, @NotNull List<QueryPoint> selected) throws SQLException {
        if (itemKey == null) return null;
        long boundary = selected.isEmpty() ? fromMillis : selected.get(selected.size() - 1).point.observedAt();
        QueryPoint anchor = queryBaseline(db, gridId, itemKey, boundary);
        if (anchor == null || !anchor.point.complete() || crossesGap(db, gridId, itemKey, anchor, boundary))
            return null;
        for (QueryPoint point : selected) {
            if (point.id == anchor.id) return null;
        }
        return anchor.point;
    }

    private static @NotNull List<QueryPoint> queryPoints(@NotNull Connection db, long gridId,
        @Nullable StableKey itemKey, boolean markersOnly, long fromMillis, int limit) throws SQLException {
        List<QueryPoint> result = new ArrayList<>();
        String filter = "";
        if (itemKey != null) filter = "AND p.item_key = ?\n";
        else if (markersOnly) filter = "AND p.item_key IS NULL\n";
        try (PreparedStatement query = db.prepareStatement("""
            SELECT p.*
            FROM history_points p
            WHERE p.grid_id = ?
                AND p.observed_at >= ?
            """ + filter + "ORDER BY p.observed_at DESC, p.id DESC")) {
            query.setLong(1, gridId);
            query.setLong(2, fromMillis);
            if (itemKey != null) query.setString(3, itemKey.toString());
            query.setMaxRows(limit + 1);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    StorageHistoryPoint point = readPoint(rows);
                    result.add(new QueryPoint(rows.getLong("id"), point));
                }
            }
        }
        return result;
    }

    private static boolean hasIncomplete(@NotNull Connection db, long gridId, @Nullable StableKey itemKey,
        long fromMillis) throws SQLException {
        String filter = itemKey == null ? "" : "AND (item_key = ? OR item_key IS NULL)";
        try (PreparedStatement query = db.prepareStatement("""
            SELECT id
            FROM history_points
            WHERE grid_id = ?
                AND complete = FALSE
                AND observed_at >= ?
            """ + filter)) {
            query.setLong(1, gridId);
            query.setLong(2, fromMillis);
            if (itemKey != null) query.setString(3, itemKey.toString());
            query.setMaxRows(1);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static @Nullable QueryPoint queryBaseline(@NotNull Connection db, long gridId, @NotNull StableKey itemKey,
        long boundary) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT p.*
            FROM history_points p
            WHERE p.grid_id = ?
                AND p.item_key = ?
                AND p.kind = 'STOCK'
                AND p.observed_at <= ?
            ORDER BY p.observed_at DESC, p.id DESC
            """)) {
            query.setLong(1, gridId);
            query.setString(2, itemKey.toString());
            query.setLong(3, boundary);
            query.setMaxRows(1);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                return new QueryPoint(rows.getLong("id"), readPoint(rows));
            }
        }
    }

    private static boolean crossesGap(Connection db, long gridId, StableKey itemKey, QueryPoint anchor, long boundary)
        throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT id
            FROM history_points
            WHERE grid_id = ?
                AND (observed_at > ? OR observed_at = ? AND id > ?)
                AND observed_at <= ?
                AND (
                    kind = 'GAP'
                    OR generation <> ?
                    OR complete = FALSE AND (kind = 'OBSERVATION' OR item_key = ? AND kind = 'STOCK')
                )
            """)) {
            query.setLong(1, gridId);
            query.setLong(2, anchor.point.observedAt());
            query.setLong(3, anchor.point.observedAt());
            query.setLong(4, anchor.id);
            query.setLong(5, boundary);
            query.setLong(6, anchor.point.generation());
            query.setString(7, itemKey.toString());
            query.setMaxRows(1);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    @Desugar
    private record QueryPoint(long id, @NotNull StorageHistoryPoint point) {}

    private static @NotNull StorageHistoryPoint readPoint(@NotNull ResultSet row) throws SQLException {
        String itemKey = row.getString("item_key");
        return new StorageHistoryPoint(
            row.getLong("observed_at"),
            row.getLong("generation"),
            StorageHistoryPoint.Kind.valueOf(row.getString("kind")),
            itemKey == null ? null : StableKey.parse(itemKey),
            row.getLong("quantity"),
            row.getBoolean("craftable"),
            row.getLong("input_amount"),
            row.getLong("output_amount"),
            row.getBoolean("complete"));
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
