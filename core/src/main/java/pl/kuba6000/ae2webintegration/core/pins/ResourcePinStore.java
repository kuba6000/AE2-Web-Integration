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
import java.util.Objects;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
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
     * Display settings for a home-page resource pin.
     *
     * @param showStock       show the last recorded stock quantity
     * @param showFlow        show flow totals for flowRangeMillis
     * @param showChart       show the history chart
     * @param stock           show the stock series on the chart
     * @param input           show the input series on the chart
     * @param output          show the output series on the chart
     * @param net             show input minus output on the chart
     * @param rangeMillis     chart time range in milliseconds, from 60000 to 31536000000
     * @param showRecentFlow  show the two-minute flow summary
     * @param flowRangeMillis flow-total time range in milliseconds, from 60000 to 31536000000; independent of the chart
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
        @Nullable Integer homeOrder, @Nullable StableKey homeGroupKey, @NotNull HomeConfig homeConfig) {}

    @Desugar
    public record HomeGroup(@NotNull StableKey groupKey, @NotNull String name, int ordinal) {}

    @Desugar
    public record Snapshot(@NotNull List<Pin> pins, @NotNull List<HomeGroup> groups) {}

    /** Omitted selection preserves the group; a selection with no key chooses Ungrouped. */
    @Desugar
    public record HomeGroupSelection(@Nullable StableKey groupKey) {}

    private final @NotNull WorldDatabase database;

    public ResourcePinStore(@NotNull WorldDatabase database) {
        this.database = database;
    }

    private static @NotNull String owner(@NotNull WebPrincipal principal) {
        if (principal.isAdmin()) return "admin";
        PlayerIdentity player = principal.getPlayerIdentity();
        if (player == null) throw new IllegalArgumentException("Pins require an authenticated account");
        return player.uuid.toString();
    }

    public @NotNull List<Pin> list(@NotNull WebPrincipal principal) throws IOException {
        String owner = owner(principal);
        return database.read(db -> list(db, owner));
    }

    public @NotNull Snapshot snapshot(@NotNull WebPrincipal principal) throws IOException {
        String owner = owner(principal);
        return database.read(db -> new Snapshot(list(db, owner), groups(db, owner)));
    }

    private static @NotNull List<HomeGroup> groups(@NotNull Connection db, @NotNull String owner) throws SQLException {
        List<HomeGroup> groups = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement(
            "SELECT group_key, name, ordinal FROM home_pin_groups WHERE owner = ? ORDER BY ordinal, group_key")) {
            query.setString(1, owner);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next())
                    groups.add(new HomeGroup(StableKey.parse(rows.getString(1)), rows.getString(2), rows.getInt(3)));
            }
        }
        return groups;
    }

    public @NotNull HomeGroup createGroup(@NotNull WebPrincipal principal, @NotNull String name) throws IOException {
        String owner = owner(principal);
        String label = groupName(name);
        return database.write(db -> {
            List<HomeGroup> groups = groups(db, owner);
            requireUniqueName(groups, label, null);
            HomeGroup group = new HomeGroup(StableKey.random(), label, groups.size());
            try (PreparedStatement insert = db
                .prepareStatement("INSERT INTO home_pin_groups(group_key, owner, name, ordinal) VALUES (?, ?, ?, ?)")) {
                insert.setString(
                    1,
                    group.groupKey()
                        .toString());
                insert.setString(2, owner);
                insert.setString(3, label);
                insert.setInt(4, group.ordinal());
                insert.executeUpdate();
            }
            return group;
        });
    }

    private static @NotNull String groupName(@NotNull String name) {
        String label = name.trim();
        if (label.isEmpty() || label.length() > 128) throw new IllegalArgumentException("Invalid group name");
        return label;
    }

    private static void requireUniqueName(@NotNull List<HomeGroup> groups, @NotNull String name,
        @Nullable StableKey except) {
        if (groups.stream()
            .anyMatch(
                group -> group.name()
                    .equals(name)
                    && !group.groupKey()
                        .equals(except)))
            throw new IllegalArgumentException("Group name is already in use");
    }

    public boolean updateGroup(@NotNull WebPrincipal principal, @NotNull StableKey groupKey, @Nullable String name,
        @Nullable Direction direction) throws IOException {
        String owner = owner(principal);
        String label = name == null ? null : groupName(name);
        return database.write(db -> {
            List<HomeGroup> groups = groups(db, owner);
            int index = 0;
            while (index < groups.size() && !groups.get(index)
                .groupKey()
                .equals(groupKey)) index++;
            if (index == groups.size()) return false;
            if (label != null) {
                requireUniqueName(groups, label, groupKey);
                try (PreparedStatement update = db
                    .prepareStatement("UPDATE home_pin_groups SET name = ? WHERE group_key = ? AND owner = ?")) {
                    update.setString(1, label);
                    update.setString(2, groupKey.toString());
                    update.setString(3, owner);
                    update.executeUpdate();
                }
            }
            if (direction != null) {
                int adjacent = index + (direction == Direction.EARLIER ? -1 : 1);
                if (adjacent >= 0 && adjacent < groups.size()) {
                    setGroupOrder(db, groupKey, adjacent);
                    setGroupOrder(
                        db,
                        groups.get(adjacent)
                            .groupKey(),
                        index);
                }
            }
            return true;
        });
    }

    public void removeGroup(@NotNull WebPrincipal principal, @NotNull StableKey groupKey) throws IOException {
        String owner = owner(principal);
        database.write(db -> {
            if (groups(db, owner).stream()
                .noneMatch(
                    group -> group.groupKey()
                        .equals(groupKey)))
                return null;
            List<Pin> pins = list(db, owner);
            int next = placementPins(pins, Placement.HOME, null).size();
            try (PreparedStatement move = db
                .prepareStatement("UPDATE resource_pins SET home_group_key = NULL, home_order = ? WHERE pin_key = ?")) {
                for (Pin pin : placementPins(pins, Placement.HOME, groupKey)) {
                    move.setInt(1, next++);
                    move.setString(
                        2,
                        pin.pinKey()
                            .toString());
                    move.executeUpdate();
                }
            }
            try (PreparedStatement delete = db
                .prepareStatement("DELETE FROM home_pin_groups WHERE group_key = ? AND owner = ?")) {
                delete.setString(1, groupKey.toString());
                delete.setString(2, owner);
                delete.executeUpdate();
            }
            List<HomeGroup> groups = groups(db, owner);
            for (int index = 0; index < groups.size(); index++) {
                HomeGroup group = groups.get(index);
                if (group.ordinal() != index) setGroupOrder(db, group.groupKey(), index);
            }
            return null;
        });
    }

    private static void setGroupOrder(@NotNull Connection db, @NotNull StableKey groupKey, int ordinal)
        throws SQLException {
        try (PreparedStatement update = db
            .prepareStatement("UPDATE home_pin_groups SET ordinal = ? WHERE group_key = ?")) {
            update.setInt(1, ordinal);
            update.setString(2, groupKey.toString());
            update.executeUpdate();
        }
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
                            nullableInt(rows, "terminal_order"),
                            nullableInt(rows, "home_order"),
                            rows.getString("home_group_key") == null ? null
                                : StableKey.parse(rows.getString("home_group_key")),
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

    private static @Nullable Integer nullableInt(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
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
                if (order(existing, placement) == null) setOrder(
                    db,
                    existing.pinKey(),
                    placement,
                    placementPins(pins, placement, placement == Placement.HOME ? null : grid).size());
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
                int index = placementPins(pins, placement, placement == Placement.HOME ? null : grid).size();
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
                ? OR EXISTS (SELECT 1 FROM history_stock s JOIN history_measurements m ON m.id = s.measurement_id
                             WHERE m.grid_id = ? AND s.resource_id = r.id)
                  OR EXISTS (SELECT 1 FROM history_flow f JOIN history_measurements m ON m.id = f.measurement_id
                             WHERE m.grid_id = ? AND f.resource_id = r.id)
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
        @Nullable Boolean home, @Nullable Move move, @Nullable HomeConfig configuration,
        @Nullable HomeGroupSelection homeGroup) throws IOException {
        if (configuration != null) configuration.validate();
        String owner = owner(principal);
        return database.write(db -> {
            List<Pin> pins = list(db, owner);
            Pin pin = find(pins, pinKey);
            if (pin == null) return false;
            Integer terminalOrder = selectedOrder(pins, pin, Placement.TERMINAL, terminal, null);
            boolean homeEnabled = home == null ? pin.homeOrder() != null : home;
            if (homeGroup != null && !homeEnabled)
                throw new IllegalArgumentException("Cannot group a disabled Home pin");
            StableKey group = !homeEnabled ? null : homeGroup == null ? pin.homeGroupKey() : homeGroup.groupKey();
            if (homeGroup != null && group != null
                && groups(db, owner).stream()
                    .noneMatch(
                        value -> value.groupKey()
                            .equals(group)))
                throw new IllegalArgumentException("Unknown pin group");
            Integer homeOrder = selectedOrder(pins, pin, Placement.HOME, home, group);
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
                    show_recent_flow = ?, flow_range_millis = ?, home_group_key = ?
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
                update.setString(13, group == null ? null : group.toString());
                update.setString(14, pinKey.toString());
                update.setString(15, owner);
                update.executeUpdate();
            }
            if (terminal != null || home != null || homeGroup != null) {
                List<Pin> changed = list(db, owner);
                compact(db, changed, Placement.TERMINAL, pin.gridKey());
                compact(db, changed, Placement.HOME, pin.homeGroupKey());
                if (!Objects.equals(group, pin.homeGroupKey())) compact(db, changed, Placement.HOME, group);
            }
            if (move != null) {
                List<Pin> ordered = placementPins(
                    list(db, owner),
                    move.placement(),
                    move.placement() == Placement.HOME ? group : pin.gridKey());
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
        @NotNull Placement placement, @Nullable Boolean enabled, @Nullable StableKey group) {
        Integer order = order(pin, placement);
        if (Boolean.FALSE.equals(enabled)) return null;
        if (enabled == null && order == null) return null;
        return order == null || (placement == Placement.HOME && !Objects.equals(group, pin.homeGroupKey())) ? Integer
            .valueOf(placementPins(pins, placement, placement == Placement.HOME ? group : pin.gridKey()).size())
            : order;
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
        List<Pin> remaining = list(db, owner);
        compact(db, remaining, Placement.TERMINAL, pin.gridKey());
        compact(db, remaining, Placement.HOME, pin.homeGroupKey());
        if (resourceId != null) ResourceCatalogue.removeUnreferenced(db, Collections.singleton(resourceId));
    }

    private static void compact(@NotNull Connection db, @NotNull List<Pin> pins, @NotNull Placement placement,
        @Nullable StableKey placementKey) throws SQLException {
        List<Pin> ordered = placementPins(pins, placement, placementKey);
        for (int index = 0; index < ordered.size(); index++) {
            Pin pin = ordered.get(index);
            if (order(pin, placement) != index) setOrder(db, pin.pinKey(), placement, index);
        }
    }

    private static @NotNull List<Pin> placementPins(@NotNull List<Pin> pins, @NotNull Placement placement,
        @Nullable StableKey placementKey) {
        return pins.stream()
            .filter(
                pin -> order(pin, placement) != null
                    && Objects.equals(placementKey, placement == Placement.HOME ? pin.homeGroupKey() : pin.gridKey()))
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
