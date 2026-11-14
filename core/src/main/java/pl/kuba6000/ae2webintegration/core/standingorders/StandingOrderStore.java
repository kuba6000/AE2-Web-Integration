package pl.kuba6000.ae2webintegration.core.standingorders;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.ResourceCatalogue;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException.Reason;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderCodec.Encoded;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Standing orders of each grid. A definition is stored as its codec document plus references to the world's resource
 * catalogue; all values are detached and writes share the world transaction gate. Orders belong to their grid, so
 * every operation is scoped by grid key.
 */
public final class StandingOrderStore {

    public static final class OrderLimitReached extends IllegalArgumentException {

        @SuppressWarnings("MissingSerialAnnotation") // @Serial is unavailable on Java 8.
        private static final long serialVersionUID = 1L;

        private OrderLimitReached() {
            super("Standing order limit reached");
        }
    }

    /** A definition refers to a resource that is neither supplied nor already in the catalogue. */
    public static final class UnknownResource extends IllegalArgumentException {

        @SuppressWarnings("MissingSerialAnnotation") // @Serial is unavailable on Java 8.
        private static final long serialVersionUID = 1L;

        private UnknownResource(@NotNull StableKey resource) {
            super("Unknown resource " + resource);
        }
    }

    /** One stored order: either decoded, or kept visible with the reason it cannot be read. */
    public interface Entry {

        @NotNull
        StableKey key();

        /** Display position within the grid; later orders have larger values, gaps are possible. */
        int ordinal();

        /** The creating account's UUID, or {@code admin}. Audit and display only. */
        @NotNull
        String createdBy();
    }

    @Desugar
    public record Valid(@NotNull StableKey key, int ordinal, @NotNull String createdBy,
        @NotNull StandingOrderDefinition definition) implements Entry {}

    /** The stored document cannot be decoded by this version, for example after an unsupported format version. */
    @Desugar
    public record Invalid(@NotNull StableKey key, int ordinal, @NotNull String createdBy,
        @NotNull InvalidStandingOrderException failure) implements Entry {}

    /**
     * A grid's orders together with the catalogue descriptions of every resource they refer to, read in one
     * transaction.
     */
    @Desugar
    public record Snapshot(@NotNull List<Entry> orders, @NotNull Map<StableKey, ResourceDescriptor> resources) {}

    private final @NotNull WorldDatabase database;
    private final @NotNull IntSupplier maxOrdersPerGrid;

    /** @param maxOrdersPerGrid read for every creation, so a reloaded configuration applies immediately */
    public StandingOrderStore(@NotNull WorldDatabase database, @NotNull IntSupplier maxOrdersPerGrid) {
        this.database = database;
        this.maxOrdersPerGrid = maxOrdersPerGrid;
    }

    /** The grid's orders in display order; an unknown grid has none. */
    public @NotNull List<Entry> list(@NotNull StableKey grid) throws IOException {
        return database.read(db -> load(db, grid, null));
    }

    /** The grid's orders in display order with their resources' descriptions; an unknown grid has neither. */
    public @NotNull Snapshot snapshot(@NotNull StableKey grid) throws IOException {
        return database.read(db -> new Snapshot(load(db, grid, null), describe(db, grid)));
    }

    /** The orders of every grid that has any, each in display order, for loading them all at server start. */
    public @NotNull Map<StableKey, List<Entry>> listAll() throws IOException {
        return database.read(db -> {
            List<StableKey> grids = new ArrayList<>();
            try (PreparedStatement query = db.prepareStatement("""
                SELECT g.grid_key FROM grids g
                WHERE EXISTS (SELECT 1 FROM standing_orders o WHERE o.grid_id = g.id)
                ORDER BY g.id
                """); ResultSet rows = query.executeQuery()) {
                while (rows.next()) grids.add(StableKey.parse(rows.getString(1)));
            }
            Map<StableKey, List<Entry>> result = new LinkedHashMap<>();
            for (StableKey grid : grids) result.put(grid, load(db, grid, null));
            return result;
        });
    }

    public @Nullable Entry get(@NotNull StableKey grid, @NotNull StableKey order) throws IOException {
        List<Entry> entries = database.read(db -> load(db, grid, order));
        return entries.isEmpty() ? null : entries.get(0);
    }

    /**
     * Appends a new order after the grid's others.
     *
     * @param descriptions current descriptions of resources the definition may refer to; a referenced resource
     *                     without one must already be in the catalogue
     * @throws InvalidStandingOrderException the definition violates the format or a limit
     * @throws OrderLimitReached             the grid already holds the configured maximum
     * @throws UnknownResource               a referenced resource is neither described nor in the catalogue
     */
    public @NotNull Valid create(@NotNull StableKey grid, @NotNull WebPrincipal creator,
        @NotNull StandingOrderDefinition definition, @NotNull Map<StableKey, ResourceDescriptor> descriptions)
        throws IOException, InvalidStandingOrderException {
        String owner = owner(creator);
        Encoded encoded = encode(definition, descriptions);
        return database.write(db -> {
            Long gridId = gridId(db, grid);
            if (gridId == null) throw new IllegalArgumentException("Unknown grid");
            int count;
            int ordinal;
            try (PreparedStatement query = db.prepareStatement(
                "SELECT count(*), COALESCE(MAX(ordinal) + 1, 0) FROM standing_orders WHERE grid_id = ?")) {
                query.setLong(1, gridId);
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    count = rows.getInt(1);
                    ordinal = rows.getInt(2);
                }
            }
            if (count >= maxOrdersPerGrid.getAsInt()) throw new OrderLimitReached();
            StableKey key = StableKey.random();
            List<Long> resources = reference(db, descriptions, encoded.resources());
            try (PreparedStatement insert = db.prepareStatement("""
                INSERT INTO standing_orders (order_key, grid_id, ordinal, created_by, definition, definition_version)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
                insert.setString(1, key.toString());
                insert.setLong(2, gridId);
                insert.setInt(3, ordinal);
                insert.setString(4, owner);
                insert.setString(5, encoded.json());
                insert.setInt(6, encoded.version());
                insert.executeUpdate();
            }
            insertResources(db, key, resources);
            return new Valid(key, ordinal, owner, definition);
        });
    }

    /**
     * Replaces the definition of an existing order, keeping its key, position and creator.
     *
     * @return false when the grid has no such order
     */
    public boolean replace(@NotNull StableKey grid, @NotNull StableKey order,
        @NotNull StandingOrderDefinition definition, @NotNull Map<StableKey, ResourceDescriptor> descriptions)
        throws IOException, InvalidStandingOrderException {
        Encoded encoded = encode(definition, descriptions);
        return database.write(db -> {
            if (missing(db, grid, order)) return false;
            Set<Long> previous = new HashSet<>(resourceIds(db, order));
            List<Long> resources = reference(db, descriptions, encoded.resources());
            try (PreparedStatement update = db.prepareStatement(
                "UPDATE standing_orders SET definition = ?, definition_version = ? WHERE order_key = ?")) {
                update.setString(1, encoded.json());
                update.setInt(2, encoded.version());
                update.setString(3, order.toString());
                update.executeUpdate();
            }
            try (PreparedStatement delete = db
                .prepareStatement("DELETE FROM standing_order_resources WHERE order_key = ?")) {
                delete.setString(1, order.toString());
                delete.executeUpdate();
            }
            insertResources(db, order, resources);
            ResourceCatalogue.removeUnreferenced(db, previous);
            return true;
        });
    }

    /** @return false when the grid has no such order */
    public boolean delete(@NotNull StableKey grid, @NotNull StableKey order) throws IOException {
        return database.write(db -> {
            if (missing(db, grid, order)) return false;
            Set<Long> previous = new HashSet<>(resourceIds(db, order));
            try (PreparedStatement delete = db.prepareStatement("DELETE FROM standing_orders WHERE order_key = ?")) {
                delete.setString(1, order.toString());
                delete.executeUpdate();
            }
            ResourceCatalogue.removeUnreferenced(db, previous);
            return true;
        });
    }

    private static @NotNull String owner(@NotNull WebPrincipal principal) {
        if (principal.isAdmin()) return "admin";
        PlayerIdentity player = principal.getPlayerIdentity();
        if (player == null) throw new IllegalArgumentException("Standing orders require an authenticated account");
        return player.uuid.toString();
    }

    private static @NotNull Encoded encode(@NotNull StandingOrderDefinition definition,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions) throws InvalidStandingOrderException {
        for (Map.Entry<StableKey, ResourceDescriptor> description : descriptions.entrySet()) {
            if (!description.getKey()
                .equals(
                    description.getValue()
                        .itemKey()))
                throw new IllegalArgumentException("Mismatched resource identity");
        }
        StandingOrderValidator.validate(definition);
        return StandingOrderCodec.encode(definition);
    }

    private static @Nullable Long gridId(@NotNull Connection db, @NotNull StableKey grid) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("SELECT id FROM grids WHERE grid_key = ?")) {
            query.setString(1, grid.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private static boolean missing(@NotNull Connection db, @NotNull StableKey grid, @NotNull StableKey order)
        throws SQLException {
        try (PreparedStatement query = db.prepareStatement("""
            SELECT 1 FROM standing_orders o JOIN grids g ON g.id = o.grid_id
            WHERE g.grid_key = ? AND o.order_key = ?
            """)) {
            query.setString(1, grid.toString());
            query.setString(2, order.toString());
            try (ResultSet rows = query.executeQuery()) {
                return !rows.next();
            }
        }
    }

    private static @NotNull List<Long> resourceIds(@NotNull Connection db, @NotNull StableKey order)
        throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement query = db
            .prepareStatement("SELECT resource_id FROM standing_order_resources WHERE order_key = ?")) {
            query.setString(1, order.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    /** Stores the supplied descriptions of referenced resources and resolves every slot to its catalogue row. */
    private static @NotNull List<Long> reference(@NotNull Connection db,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, @NotNull List<StableKey> resources)
        throws SQLException {
        Map<StableKey, ResourceDescriptor> supplied = new HashMap<>();
        for (StableKey resource : resources) {
            ResourceDescriptor description = descriptions.get(resource);
            if (description != null) supplied.put(resource, description);
        }
        if (!supplied.isEmpty()) ResourceCatalogue.upsert(db, supplied, supplied.keySet());
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement("SELECT id FROM history_resources WHERE item_key = ?")) {
            for (StableKey resource : resources) {
                query.setString(1, resource.toString());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new UnknownResource(resource);
                    ids.add(rows.getLong(1));
                }
            }
        }
        return ids;
    }

    private static void insertResources(@NotNull Connection db, @NotNull StableKey order, @NotNull List<Long> resources)
        throws SQLException {
        try (PreparedStatement insert = db
            .prepareStatement("INSERT INTO standing_order_resources (order_key, slot, resource_id) VALUES (?, ?, ?)")) {
            for (int slot = 0; slot < resources.size(); slot++) {
                insert.setString(1, order.toString());
                insert.setInt(2, slot);
                insert.setLong(3, resources.get(slot));
                insert.addBatch();
            }
            if (!resources.isEmpty()) insert.executeBatch();
        }
    }

    private static @NotNull List<Entry> load(@NotNull Connection db, @NotNull StableKey grid, @Nullable StableKey order)
        throws SQLException {
        String filter = order == null ? "" : " AND o.order_key = ?";
        Map<String, List<StableKey>> tables = new HashMap<>();
        Set<String> gapped = new HashSet<>();
        try (PreparedStatement query = db.prepareStatement("""
            SELECT r.order_key, r.slot, h.item_key
            FROM standing_order_resources r
            JOIN standing_orders o ON o.order_key = r.order_key
            JOIN grids g ON g.id = o.grid_id
            JOIN history_resources h ON h.id = r.resource_id
            WHERE g.grid_key = ?
            """ + filter + " ORDER BY r.order_key, r.slot")) {
            bind(query, grid, order);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString(1);
                    List<StableKey> table = tables.computeIfAbsent(key, unused -> new ArrayList<>());
                    // A stored slot table must run from 0 upwards without gaps; anything else cannot be decoded.
                    if (rows.getInt(2) == table.size()) table.add(StableKey.parse(rows.getString(3)));
                    else gapped.add(key);
                }
            }
        }
        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement("""
            SELECT o.order_key, o.ordinal, o.created_by, o.definition, o.definition_version
            FROM standing_orders o JOIN grids g ON g.id = o.grid_id
            WHERE g.grid_key = ?
            """ + filter + " ORDER BY o.ordinal, o.order_key")) {
            bind(query, grid, order);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String token = rows.getString(1);
                    StableKey key = StableKey.parse(token);
                    int ordinal = rows.getInt(2);
                    String createdBy = rows.getString(3);
                    if (gapped.contains(token)) {
                        entries.add(
                            new Invalid(
                                key,
                                ordinal,
                                createdBy,
                                new InvalidStandingOrderException(Reason.INVALID_RESOURCE_TABLE, "")));
                        continue;
                    }
                    try {
                        List<StableKey> resources = tables.getOrDefault(token, Collections.emptyList());
                        entries.add(
                            new Valid(
                                key,
                                ordinal,
                                createdBy,
                                StandingOrderCodec.decode(new Encoded(rows.getInt(5), rows.getString(4), resources))));
                    } catch (InvalidStandingOrderException failure) {
                        entries.add(new Invalid(key, ordinal, createdBy, failure));
                    }
                }
            }
        }
        return entries;
    }

    private static @NotNull Map<StableKey, ResourceDescriptor> describe(@NotNull Connection db, @NotNull StableKey grid)
        throws SQLException {
        Map<StableKey, ResourceDescriptor> descriptions = new LinkedHashMap<>();
        try (PreparedStatement query = db.prepareStatement("""
            SELECT DISTINCT h.item_key, h.registry_namespace, h.registry_path, h.components, h.damage,
                h.display_name, h.resource_type, h.base_key
            FROM standing_order_resources r
            JOIN standing_orders o ON o.order_key = r.order_key
            JOIN grids g ON g.id = o.grid_id
            JOIN history_resources h ON h.id = r.resource_id
            WHERE g.grid_key = ?
            ORDER BY h.item_key
            """)) {
            query.setString(1, grid.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    StableKey item = StableKey.parse(rows.getString(1));
                    String base = rows.getString(8);
                    descriptions.put(
                        item,
                        new ResourceDescriptor(
                            rows.getString(2),
                            rows.getString(3),
                            rows.getInt(4),
                            rows.getInt(5),
                            rows.getString(6),
                            ResourceType.valueOf(rows.getString(7)),
                            item,
                            base == null ? null : StableKey.parse(base)));
                }
            }
        }
        return descriptions;
    }

    private static void bind(@NotNull PreparedStatement query, @NotNull StableKey grid, @Nullable StableKey order)
        throws SQLException {
        query.setString(1, grid.toString());
        if (order != null) query.setString(2, order.toString());
    }
}
