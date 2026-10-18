package pl.kuba6000.ae2webintegration.core.pins;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.ResourceCatalogue;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Account-owned resource placements; all values are detached and writes share the world transaction gate. */
public final class ResourcePinStore {

    public enum Placement {

        TERMINAL,
        HOME;

        public static @NotNull Placement parse(@NotNull String value) {
            if (value.equals("terminal")) return TERMINAL;
            if (value.equals("home")) return HOME;
            throw new IllegalArgumentException("Unknown pin placement");
        }
    }

    public enum Direction {

        EARLIER,
        LATER;

        public static @NotNull Direction parse(@NotNull String value) {
            if (value.equals("earlier")) return EARLIER;
            if (value.equals("later")) return LATER;
            throw new IllegalArgumentException("Unknown pin direction");
        }
    }

    @Desugar
    public record Move(@NotNull Placement placement, @NotNull Direction direction) {}

    /**
     * Independent chart and historical-flow ranges are exact milliseconds; recent flow uses the fixed hot-cache window.
     */
    @Desugar
    public record HomeConfig(boolean showStock, boolean showFlow, boolean showChart, boolean stock, boolean input,
        boolean output, boolean net, long rangeMillis, boolean showRecentFlow, long flowRangeMillis) {

        public void validate() {
            if (rangeMillis < 60000 || rangeMillis > 31536000000L
                || flowRangeMillis < 60000
                || flowRangeMillis > 31536000000L) throw new IllegalArgumentException("Invalid pin history range");
        }
    }

    @Desugar
    public record Pin(@NotNull StableKey pinKey, @NotNull StableKey gridKey, @NotNull StableKey itemKey,
        @NotNull String gridName, @NotNull ResourceDescriptor resource, @Nullable Integer terminalOrder,
        @Nullable Integer homeOrder, @NotNull HomeConfig homeConfig) {}

    private final @NotNull WorldDatabase database;

    public ResourcePinStore(@NotNull WorldDatabase database) {
        this.database = database;
    }

    private static @NotNull String owner(@NotNull WebPrincipal principal) {
        if (principal.isAdmin()) return "admin";
        var player = principal.getPlayerIdentity();
        if (player == null) throw new IllegalArgumentException("Pins require an authenticated account");
        return player.uuid.toString();
    }

    public @NotNull List<Pin> list(@NotNull WebPrincipal principal) throws IOException {
        String owner = owner(principal);
        return database.read(db -> list(db, owner));
    }

    private static @NotNull List<Pin> list(@NotNull Connection db, @NotNull String owner) throws SQLException {
        List<Pin> pins = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement("""
            SELECT p.*, g.grid_key, g.name AS grid_name, r.*
            FROM resource_pins p
            JOIN grids g ON g.id = p.grid_id
            JOIN history_resources r ON r.id = p.resource_id
            WHERE p.owner = ?
            ORDER BY p.pin_key
            """)) {
            query.setString(1, owner);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    StableKey item = StableKey.parse(rows.getString("item_key"));
                    String base = rows.getString("base_key");
                    pins.add(
                        new Pin(
                            StableKey.parse(rows.getString("pin_key")),
                            StableKey.parse(rows.getString("grid_key")),
                            item,
                            rows.getString("grid_name"),
                            new ResourceDescriptor(
                                rows.getString("registry_namespace"),
                                rows.getString("registry_path"),
                                rows.getInt("components"),
                                rows.getInt("damage"),
                                rows.getString("display_name"),
                                ResourceType.valueOf(rows.getString("resource_type")),
                                item,
                                base == null ? null : StableKey.parse(base)),
                            (Integer) rows.getObject("terminal_order"),
                            (Integer) rows.getObject("home_order"),
                            new HomeConfig(
                                rows.getBoolean("show_stock"),
                                rows.getBoolean("show_flow"),
                                rows.getBoolean("show_chart"),
                                rows.getBoolean("series_stock"),
                                rows.getBoolean("series_input"),
                                rows.getBoolean("series_output"),
                                rows.getBoolean("series_net"),
                                rows.getLong("range_millis"),
                                rows.getBoolean("show_recent_flow"),
                                rows.getLong("flow_range_millis"))));
                }
            }
        }
        return pins;
    }

    public boolean add(@NotNull WebPrincipal principal, @NotNull StableKey grid, @NotNull StableKey item,
        @Nullable ResourceDescriptor currentDescription, @NotNull Placement placement) throws IOException {
        String owner = owner(principal);
        if (currentDescription != null && !item.equals(currentDescription.itemKey()))
            throw new IllegalArgumentException("Mismatched resource identity");
        return database.write(db -> {
            Long gridId = lookup(db, "SELECT id FROM grids WHERE grid_key = ?", grid);
            if (gridId == null) return false;
            if (currentDescription != null) ResourceCatalogue
                .upsert(db, Collections.singletonMap(item, currentDescription), Collections.singleton(item));
            Long resourceId = admissibleResource(db, owner, gridId, item, currentDescription != null);
            if (resourceId == null) return false;
            List<Pin> pins = list(db, owner);
            Pin existing = pins.stream()
                .filter(
                    pin -> pin.gridKey()
                        .equals(grid)
                        && pin.itemKey()
                            .equals(item))
                .findFirst()
                .orElse(null);
            if (existing != null) {
                if (order(existing, placement) == null)
                    setOrder(db, existing.pinKey(), placement, placementPins(pins, placement, grid).size());
                return true;
            }
            try (PreparedStatement insert = db.prepareStatement(
                """
                    INSERT INTO resource_pins (
                        pin_key, owner, grid_id, resource_id, terminal_order, home_order,
                        show_stock, show_flow, show_chart, series_stock, series_input, series_output, series_net, range_millis,
                        show_recent_flow, flow_range_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, FALSE, 86400000, TRUE, 86400000)
                    """)) {
                insert.setString(
                    1,
                    StableKey.random()
                        .toString());
                insert.setString(2, owner);
                insert.setLong(3, gridId);
                insert.setLong(4, resourceId);
                int index = placementPins(pins, placement, grid).size();
                insert.setObject(5, placement == Placement.TERMINAL ? index : null, Types.INTEGER);
                insert.setObject(6, placement == Placement.HOME ? index : null, Types.INTEGER);
                insert.executeUpdate();
            }
            return true;
        });
    }

    private static @Nullable Long admissibleResource(@NotNull Connection db, @NotNull String owner, long gridId,
        @NotNull StableKey item, boolean current) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT r.id
            FROM history_resources r
            WHERE r.item_key = ? AND (
                ? OR EXISTS (SELECT 1 FROM history_stock s WHERE s.grid_id = ? AND s.resource_id = r.id)
                  OR EXISTS (SELECT 1 FROM history_flow f WHERE f.grid_id = ? AND f.resource_id = r.id)
                  OR EXISTS (SELECT 1 FROM resource_pins p WHERE p.owner = ? AND p.grid_id = ? AND p.resource_id = r.id)
            )
            """)) {
            query.setString(1, item.toString());
            query.setBoolean(2, current);
            query.setLong(3, gridId);
            query.setLong(4, gridId);
            query.setString(5, owner);
            query.setLong(6, gridId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    public boolean update(@NotNull WebPrincipal principal, @NotNull StableKey pinKey, @Nullable Boolean terminal,
        @Nullable Boolean home, @Nullable Move move, @Nullable HomeConfig configuration) throws IOException {
        if (configuration != null) configuration.validate();
        String owner = owner(principal);
        return database.write(db -> {
            List<Pin> pins = list(db, owner);
            Pin pin = find(pins, pinKey);
            if (pin == null) return false;
            Integer terminalOrder = selectedOrder(pins, pin, Placement.TERMINAL, terminal);
            Integer homeOrder = selectedOrder(pins, pin, Placement.HOME, home);
            if (move != null && (move.placement() == Placement.TERMINAL ? terminalOrder : homeOrder) == null)
                throw new IllegalArgumentException("Cannot move a disabled placement");
            if (terminalOrder == null && homeOrder == null) {
                remove(db, owner, pin);
                return true;
            }
            HomeConfig config = configuration == null ? pin.homeConfig() : configuration;
            try (PreparedStatement update = db.prepareStatement("""
                UPDATE resource_pins
                SET terminal_order = ?, home_order = ?, show_stock = ?, show_flow = ?, show_chart = ?,
                    series_stock = ?, series_input = ?, series_output = ?, series_net = ?, range_millis = ?,
                    show_recent_flow = ?, flow_range_millis = ?
                WHERE pin_key = ? AND owner = ?
                """)) {
                update.setObject(1, terminalOrder, Types.INTEGER);
                update.setObject(2, homeOrder, Types.INTEGER);
                update.setBoolean(3, config.showStock());
                update.setBoolean(4, config.showFlow());
                update.setBoolean(5, config.showChart());
                update.setBoolean(6, config.stock());
                update.setBoolean(7, config.input());
                update.setBoolean(8, config.output());
                update.setBoolean(9, config.net());
                update.setLong(10, config.rangeMillis());
                update.setBoolean(11, config.showRecentFlow());
                update.setLong(12, config.flowRangeMillis());
                update.setString(13, pinKey.toString());
                update.setString(14, owner);
                update.executeUpdate();
            }
            if (terminal != null || home != null) compact(db, owner, pin.gridKey());
            if (move != null) {
                List<Pin> ordered = placementPins(list(db, owner), move.placement(), pin.gridKey());
                int index = 0;
                while (!ordered.get(index)
                    .pinKey()
                    .equals(pinKey)) index++;
                int adjacent = index + (move.direction() == Direction.EARLIER ? -1 : 1);
                if (adjacent >= 0 && adjacent < ordered.size()) {
                    setOrder(db, pinKey, move.placement(), adjacent);
                    setOrder(
                        db,
                        ordered.get(adjacent)
                            .pinKey(),
                        move.placement(),
                        index);
                }
            }
            return true;
        });
    }

    private static @Nullable Integer selectedOrder(@NotNull List<Pin> pins, @NotNull Pin pin,
        @NotNull Placement placement, @Nullable Boolean enabled) {
        Integer order = order(pin, placement);
        if (enabled == null) return order;
        return !enabled ? null
            : order == null ? Integer.valueOf(placementPins(pins, placement, pin.gridKey()).size()) : order;
    }

    public void remove(@NotNull WebPrincipal principal, @NotNull StableKey pinKey) throws IOException {
        String owner = owner(principal);
        database.write(db -> {
            Pin pin = find(list(db, owner), pinKey);
            if (pin != null) remove(db, owner, pin);
            return null;
        });
    }

    private static void remove(@NotNull Connection db, @NotNull String owner, @NotNull Pin pin) throws SQLException {
        Long resourceId = lookup(db, "SELECT id FROM history_resources WHERE item_key = ?", pin.itemKey());
        try (PreparedStatement delete = db
            .prepareStatement("DELETE FROM resource_pins WHERE pin_key = ? AND owner = ?")) {
            delete.setString(
                1,
                pin.pinKey()
                    .toString());
            delete.setString(2, owner);
            delete.executeUpdate();
        }
        compact(db, owner, pin.gridKey());
        if (resourceId != null) ResourceCatalogue.removeUnreferenced(db, Collections.singleton(resourceId));
    }

    private static void compact(@NotNull Connection db, @NotNull String owner, @NotNull StableKey grid)
        throws SQLException {
        List<Pin> pins = list(db, owner);
        for (Placement placement : Placement.values()) {
            List<Pin> ordered = placementPins(pins, placement, grid);
            for (int index = 0; index < ordered.size(); index++) {
                Pin pin = ordered.get(index);
                if (order(pin, placement) != index) setOrder(db, pin.pinKey(), placement, index);
            }
        }
    }

    private static @NotNull List<Pin> placementPins(@NotNull List<Pin> pins, @NotNull Placement placement,
        @NotNull StableKey grid) {
        return pins.stream()
            .filter(
                pin -> order(pin, placement) != null && (placement == Placement.HOME || pin.gridKey()
                    .equals(grid)))
            .sorted(Comparator.comparingInt(pin -> order(pin, placement)))
            .collect(Collectors.toList());
    }

    private static @Nullable Integer order(@NotNull Pin pin, @NotNull Placement placement) {
        return placement == Placement.TERMINAL ? pin.terminalOrder() : pin.homeOrder();
    }

    private static @Nullable Pin find(@NotNull List<Pin> pins, @NotNull StableKey key) {
        return pins.stream()
            .filter(
                pin -> pin.pinKey()
                    .equals(key))
            .findFirst()
            .orElse(null);
    }

    private static void setOrder(@NotNull Connection db, @NotNull StableKey key, @NotNull Placement placement,
        int order) throws SQLException {
        String column = placement == Placement.TERMINAL ? "terminal_order" : "home_order";
        try (PreparedStatement update = db
            .prepareStatement("UPDATE resource_pins SET " + column + " = ? WHERE pin_key = ?")) {
            update.setInt(1, order);
            update.setString(2, key.toString());
            update.executeUpdate();
        }
    }

    private static @Nullable Long lookup(@NotNull Connection db, @NotNull String sql, @NotNull StableKey key)
        throws SQLException {
        try (PreparedStatement query = db.prepareStatement(sql)) {
            query.setString(1, key.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }
}
