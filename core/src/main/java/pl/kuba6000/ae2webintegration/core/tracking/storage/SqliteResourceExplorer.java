package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.sqlite.Function;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore.Activity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore.ResourceFilter;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore.ResourcePage;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore.ResourceSort;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore.Stock;

/** Read one grid's retained catalog and rank it by recorded transfers before paging. */
final class SqliteResourceExplorer {

    private SqliteResourceExplorer() {}

    static ResourcePage read(Connection db, StableKey grid, @Nullable Long after, ResourceFilter filter, int limit)
        throws SQLException {
        // Each history reader owns its connection. SQLite clones this aggregate for each GROUP BY accumulator.
        Function.create(db, "exact_sum", new ExactSum(), 1, Function.FLAG_DETERMINISTIC);
        try (PreparedStatement query = db.prepareStatement(
            """
                WITH network AS (
                    SELECT id FROM grids WHERE grid_key = ?1
                ), measurements AS (
                    SELECT m.id, observed_at FROM network n JOIN history_measurements m ON m.grid_id = n.id
                    WHERE observed_at <= ?2
                ), stock_times AS (
                    SELECT resource_id, MAX(observed_at) seen FROM measurements m
                    JOIN history_stock s ON s.measurement_id = m.id GROUP BY resource_id
                ), latest_stock AS (
                    SELECT resource_id, seen, (SELECT s.quantity
                        FROM network n CROSS JOIN history_measurements m ON m.grid_id = n.id AND m.observed_at = st.seen
                        CROSS JOIN history_stock s ON s.measurement_id = m.id AND s.resource_id = st.resource_id
                        ORDER BY s.id DESC LIMIT 1) quantity FROM stock_times st
                ), transfers AS (
                    SELECT resource_id, MAX(observed_at) seen, MAX(observed_at) > ?3 has_flow,
                        exact_sum(input_amount) FILTER (WHERE observed_at > ?3) incoming,
                        exact_sum(output_amount) FILTER (WHERE observed_at > ?3) outgoing,
                        exact_sum(input_operations) FILTER (WHERE observed_at > ?3) inserts,
                        exact_sum(output_operations) FILTER (WHERE observed_at > ?3) extracts
                    FROM measurements m JOIN history_flow f ON f.measurement_id = m.id GROUP BY resource_id
                ), samples AS (
                    SELECT resource_id, seen FROM stock_times UNION ALL SELECT resource_id, seen FROM transfers
                ), retained AS (
                    SELECT resource_id, MAX(seen) seen FROM samples GROUP BY resource_id
                ), candidates AS (
                    SELECT r.id, r.display_name, retained.seen, ls.quantity, ls.seen stock_at, t.has_flow, t.incoming, t.outgoing,
                        t.inserts, t.extracts
                    FROM retained JOIN history_resources r ON r.id = retained.resource_id
                    LEFT JOIN transfers t ON t.resource_id = r.id
                    LEFT JOIN latest_stock ls ON ls.resource_id = r.id
                    WHERE (?4 IS NULL OR r.resource_type = ?4) AND (?5 IS NULL OR instr(lower(r.display_name), lower(?5)) > 0
                        OR instr(lower(coalesce(r.registry_namespace, '') || ':' || coalesce(r.registry_path, '')), lower(?5)) > 0)
                ), ranked AS (
                    SELECT *, row_number() OVER (ORDER BY %s, id) AS ordinal FROM candidates
                )
                SELECT r.*, seen, quantity, stock_at, incoming, outgoing, inserts, extracts, has_flow
                FROM ranked JOIN history_resources r ON r.id = ranked.id
                WHERE ?6 IS NULL OR ordinal > (SELECT ordinal FROM ranked WHERE id = ?6)
                ORDER BY ordinal LIMIT ?7
                """
                .replace("%s", order(filter.sort())))) {
            query.setString(1, grid.toString());
            query.setLong(2, filter.until());
            query.setLong(3, filter.from());
            query.setString(
                4,
                filter.type() == null ? null
                    : filter.type()
                        .name());
            query.setString(5, filter.search());
            query.setObject(6, after, Types.BIGINT);
            query.setInt(7, limit + 1);
            try (ResultSet rows = query.executeQuery()) {
                List<ResourceDescriptor> entries = new ArrayList<>();
                Map<String, Long> seen = new HashMap<>();
                Map<String, Activity> activity = new HashMap<>();
                Map<String, Stock> stock = new HashMap<>();
                Long cursor = null;
                while (rows.next()) {
                    if (entries.size() == limit) return new ResourcePage(entries, cursor, seen, activity, stock);
                    entries.add(SqliteHistoryReader.readDescription(rows));
                    String key = rows.getString("item_key");
                    seen.put(key, rows.getLong("seen"));
                    String quantity = rows.getString("quantity");
                    if (quantity != null) stock.put(key, new Stock(quantity, rows.getLong("stock_at")));
                    if (rows.getBoolean("has_flow")) activity.put(
                        key,
                        new Activity(
                            rows.getString("incoming"),
                            rows.getString("outgoing"),
                            rows.getString("inserts"),
                            rows.getString("extracts")));
                    cursor = rows.getLong("id");
                }
                return new ResourcePage(entries, null, seen, activity, stock);
            }
        }
    }

    private static String order(ResourceSort sort) {
        return switch (sort) {
            case NAME -> "display_name COLLATE NOCASE";
            case NAME_DESC -> "display_name COLLATE NOCASE DESC";
            case RECENT -> "seen DESC";
            case STOCK -> "quantity DESC";
            case INPUT -> decimalOrder("incoming");
            case OUTPUT -> decimalOrder("outgoing");
            case INSERTS -> decimalOrder("inserts");
            case EXTRACTS -> decimalOrder("extracts");
        };
    }

    /** Unsigned canonical decimals compare numerically by length then digits, without conversion to REAL. */
    private static String decimalOrder(String column) {
        return "has_flow IS 1 DESC, length(" + column + ") DESC, " + column + " DESC";
    }

    private static final class ExactSum extends Function.Aggregate {

        private BigInteger sum = BigInteger.ZERO;

        @Override
        protected void xStep() throws SQLException {
            sum = sum.add(BigInteger.valueOf(value_long(0)));
        }

        @Override
        protected void xFinal() throws SQLException {
            result(sum.toString());
        }
    }
}
