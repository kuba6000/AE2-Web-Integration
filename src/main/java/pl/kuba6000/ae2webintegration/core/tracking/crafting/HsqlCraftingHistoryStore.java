package pl.kuba6000.ae2webintegration.core.tracking.crafting;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.CraftingHistory;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.ResourceCatalogue;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.persistence.WorldGridStore;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Completed jobs use the same world transactions and resource catalogue as stock/flow history. */
public final class HsqlCraftingHistoryStore {

    private final WorldDatabase database;

    public HsqlCraftingHistoryStore(@NotNull WorldDatabase database) {
        this.database = database;
    }

    @Desugar
    public record Summary(long id, @NotNull ResourceSnapshot finalOutput, long timeStarted, long timeDone,
        boolean wasCancelled) {}

    @Desugar
    public record Page(@NotNull List<Summary> entries, @Nullable Long nextBefore) {}

    public long append(@NotNull StableKey grid, @NotNull CompletedCrafting job) throws IOException {
        return database.write(db -> {
            CraftingHistory policy = database.craftingPolicy();
            WorldGridStore.normalizeCraftingPolicy(db, policy);
            long gridId = gridId(db, grid);
            if (gridId < 0) throw new IOException("Crafting history requires a durable grid identity");
            Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
            describe(descriptions, job.finalOutput());
            for (CompletedCrafting.Resource resource : job.resources()) describe(descriptions, resource.snapshot());
            if (!descriptions.isEmpty()) ResourceCatalogue.upsert(db, descriptions, descriptions.keySet());
            long id;
            try (PreparedStatement insert = db.prepareStatement("""
                INSERT INTO crafting_jobs(grid_id, time_started, time_done, cancelled) VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, gridId);
                insert.setLong(2, job.timeStarted());
                insert.setLong(3, job.timeDone());
                insert.setBoolean(4, job.wasCancelled());
                insert.executeUpdate();
                id = generatedId(insert);
            }
            Map<String, Long> providers = new HashMap<>();
            int ordinal = 0;
            for (CompletedCrafting.Provider provider : job.providers()) {
                long providerId = insertProvider(db, id, ordinal++, provider);
                providers.put(provider.name(), providerId);
            }
            long product = insertResource(db, id, 0, job.finalOutput(), 0);
            try (PreparedStatement insert = db
                .prepareStatement("INSERT INTO crafting_products(job_id, crafting_resource_id) VALUES (?, ?)")) {
                insert.setLong(1, id);
                insert.setLong(2, product);
                insert.executeUpdate();
            }
            ordinal = 1;
            for (CompletedCrafting.Resource resource : job.resources()) {
                long resourceId = insertResource(db, id, ordinal++, resource.snapshot(), resource.timeSpentOn());
                insertIntervals(
                    db,
                    "crafting_resource_intervals",
                    "crafting_resource_id",
                    resourceId,
                    resource.intervals());
                try (PreparedStatement link = db.prepareStatement("""
                    INSERT INTO crafting_resource_providers(job_id, crafting_resource_id, provider_id) VALUES (?, ?, ?)
                    """)) {
                    for (String name : resource.providers()) {
                        Long provider = providers.get(name);
                        if (provider == null) throw new SQLException("Crafting resource references an absent provider");
                        link.setLong(1, id);
                        link.setLong(2, resourceId);
                        link.setLong(3, provider);
                        link.addBatch();
                    }
                    if (!resource.providers()
                        .isEmpty()) link.executeBatch();
                }
            }
            prune(db, gridId, System.currentTimeMillis(), policy.historyMaxJobsPerGrid);
            return id;
        });
    }

    private static void describe(Map<StableKey, ResourceDescriptor> descriptions, ResourceSnapshot resource) {
        ResourceDescriptor description = resource.description();
        if (description.itemKey() != null) descriptions.put(description.itemKey(), description);
    }

    private static long generatedId(PreparedStatement insert) throws SQLException {
        try (ResultSet keys = insert.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("Missing generated crafting identity");
            return keys.getLong(1);
        }
    }

    private static long insertResource(Connection db, long job, int ordinal, ResourceSnapshot snapshot, long timeSpent)
        throws SQLException {
        ResourceDescriptor description = snapshot.description();
        long id;
        try (PreparedStatement insert = db.prepareStatement("""
            INSERT INTO crafting_resources(job_id, resource_id, ordinal, quantity, time_spent)
            VALUES (?, (SELECT id FROM history_resources WHERE item_key = ?), ?, ?, ?)
            """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, job);
            insert.setString(
                2,
                description.itemKey() == null ? null
                    : description.itemKey()
                        .toString());
            insert.setInt(3, ordinal);
            insert.setLong(4, snapshot.quantity());
            insert.setLong(5, timeSpent);
            insert.executeUpdate();
            id = generatedId(insert);
        }
        if (description.itemKey() == null) {
            try (PreparedStatement insert = db.prepareStatement("""
                INSERT INTO crafting_resource_descriptions(crafting_resource_id, registry_namespace, registry_path,
                    components, damage, display_name, resource_type) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
                insert.setLong(1, id);
                insert.setString(2, description.registryNamespace());
                insert.setString(3, description.registryPath());
                insert.setInt(4, description.componentCount());
                insert.setInt(5, description.damage());
                insert.setString(6, description.displayName());
                insert.setString(
                    7,
                    description.resourceType()
                        .name());
                insert.executeUpdate();
            }
        }
        return id;
    }

    private static long insertProvider(Connection db, long job, int ordinal, CompletedCrafting.Provider provider)
        throws SQLException {
        long id;
        try (PreparedStatement insert = db.prepareStatement(
            "INSERT INTO crafting_providers(job_id, ordinal, name) VALUES (?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, job);
            insert.setInt(2, ordinal);
            insert.setString(3, provider.name());
            insert.executeUpdate();
            id = generatedId(insert);
        }
        try (PreparedStatement insert = db.prepareStatement(
            """
                INSERT INTO crafting_provider_locations(provider_id, ordinal, dimension_id, x, y, z) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            int locationOrdinal = 0;
            for (DimensionalCoords location : provider.locations()) {
                insert.setLong(1, id);
                insert.setInt(2, locationOrdinal++);
                insert.setString(3, location.dimensionId());
                insert.setInt(4, location.x());
                insert.setInt(5, location.y());
                insert.setInt(6, location.z());
                insert.addBatch();
            }
            if (!provider.locations()
                .isEmpty()) insert.executeBatch();
        }
        insertIntervals(db, "crafting_provider_intervals", "provider_id", id, provider.intervals());
        return id;
    }

    private static void insertIntervals(Connection db, String table, String ownerColumn, long owner,
        List<CompletedCrafting.Interval> intervals) throws SQLException {
        try (PreparedStatement insert = db.prepareStatement(
            "INSERT INTO " + table + "(" + ownerColumn + ", ordinal, started, ended) VALUES (?, ?, ?, ?)")) {
            int ordinal = 0;
            for (CompletedCrafting.Interval interval : intervals) {
                insert.setLong(1, owner);
                insert.setInt(2, ordinal++);
                insert.setLong(3, interval.started());
                insert.setLong(4, interval.ended());
                insert.addBatch();
                if (ordinal % 1000 == 0) {
                    insert.executeBatch();
                    insert.clearBatch();
                }
            }
            if (ordinal % 1000 != 0) insert.executeBatch();
        }
    }

    public @NotNull Page list(@NotNull StableKey grid, @Nullable Long before, int limit) throws IOException {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("Crafting history limit must be between 1 and 100");
        return database.read(db -> {
            List<Summary> entries = new ArrayList<>();
            try (PreparedStatement query = db.prepareStatement(
                """
                    SELECT j.id job_id, j.time_started, j.time_done, j.cancelled, r.quantity, c.item_key, c.base_key,
                        CASE WHEN r.resource_id IS NULL THEN d.registry_namespace ELSE c.registry_namespace END registry_namespace,
                        CASE WHEN r.resource_id IS NULL THEN d.registry_path ELSE c.registry_path END registry_path,
                        CASE WHEN r.resource_id IS NULL THEN d.components ELSE c.components END components,
                        CASE WHEN r.resource_id IS NULL THEN d.damage ELSE c.damage END damage,
                        CASE WHEN r.resource_id IS NULL THEN d.display_name ELSE c.display_name END display_name,
                        CASE WHEN r.resource_id IS NULL THEN d.resource_type ELSE c.resource_type END resource_type
                    FROM crafting_jobs j JOIN grids g ON j.grid_id = g.id
                    JOIN crafting_products p ON p.job_id = j.id
                    JOIN crafting_resources r ON r.id = p.crafting_resource_id
                    LEFT JOIN history_resources c ON c.id = r.resource_id
                    LEFT JOIN crafting_resource_descriptions d ON d.crafting_resource_id = r.id
                    WHERE g.grid_key = ? AND j.id < ? ORDER BY j.id DESC
                    """)) {
                query.setString(1, grid.toString());
                query.setLong(2, before == null ? Long.MAX_VALUE : before);
                query.setMaxRows(limit + 1);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) entries.add(
                        new Summary(
                            rows.getLong("job_id"),
                            snapshot(rows),
                            rows.getLong("time_started"),
                            rows.getLong("time_done"),
                            rows.getBoolean("cancelled")));
                }
            }
            Long next = entries.size() > limit ? entries.get(limit - 1)
                .id() : null;
            if (next != null) entries.remove(entries.size() - 1);
            return new Page(entries, next);
        });
    }

    public @Nullable CompletedCrafting read(@NotNull StableKey grid, long id) throws IOException {
        return database.read(db -> {
            try (PreparedStatement query = db.prepareStatement("""
                SELECT j.time_started, j.time_done, j.cancelled FROM crafting_jobs j JOIN grids g ON j.grid_id = g.id
                WHERE g.grid_key = ? AND j.id = ?
                """)) {
                query.setString(1, grid.toString());
                query.setLong(2, id);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) return null;
                    return new CompletedCrafting(
                        product(db, id),
                        rows.getLong(1),
                        rows.getLong(2),
                        rows.getBoolean(3),
                        resources(db, id),
                        providers(db, id));
                }
            }
        });
    }

    private static String descriptionsSql() {
        return """
            SELECT r.id, r.ordinal, r.quantity, r.time_spent, c.item_key, c.base_key,
                CASE WHEN r.resource_id IS NULL THEN d.registry_namespace ELSE c.registry_namespace END registry_namespace,
                CASE WHEN r.resource_id IS NULL THEN d.registry_path ELSE c.registry_path END registry_path,
                CASE WHEN r.resource_id IS NULL THEN d.components ELSE c.components END components,
                CASE WHEN r.resource_id IS NULL THEN d.damage ELSE c.damage END damage,
                CASE WHEN r.resource_id IS NULL THEN d.display_name ELSE c.display_name END display_name,
                CASE WHEN r.resource_id IS NULL THEN d.resource_type ELSE c.resource_type END resource_type
            FROM crafting_resources r LEFT JOIN history_resources c ON c.id = r.resource_id
            LEFT JOIN crafting_resource_descriptions d ON d.crafting_resource_id = r.id
            WHERE r.job_id = ?
            """;
    }

    private static ResourceSnapshot snapshot(ResultSet rows) throws SQLException {
        String item = rows.getString("item_key"), base = rows.getString("base_key");
        return new ResourceSnapshot(
            new ResourceDescriptor(
                rows.getString("registry_namespace"),
                rows.getString("registry_path"),
                rows.getInt("components"),
                rows.getInt("damage"),
                rows.getString("display_name"),
                ResourceType.valueOf(rows.getString("resource_type")),
                item == null ? null : StableKey.parse(item),
                base == null ? null : StableKey.parse(base)),
            rows.getLong("quantity"));
    }

    private static ResourceSnapshot product(Connection db, long job) throws SQLException {
        try (PreparedStatement query = db.prepareStatement(
            descriptionsSql()
                + " AND r.id = (SELECT crafting_resource_id FROM crafting_products WHERE job_id = r.job_id)")) {
            query.setLong(1, job);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Crafting history final product is missing");
                return snapshot(rows);
            }
        }
    }

    private static List<CompletedCrafting.Resource> resources(Connection db, long job) throws SQLException {
        Map<Long, List<CompletedCrafting.Interval>> intervals = readIntervals(
            db,
            "crafting_resource_intervals",
            "crafting_resource_id",
            "crafting_resources",
            job);
        Map<Long, List<String>> providers = new HashMap<>();
        try (PreparedStatement query = db.prepareStatement("""
            SELECT l.crafting_resource_id, p.name FROM crafting_resource_providers l
            JOIN crafting_resources r ON r.id = l.crafting_resource_id JOIN crafting_providers p ON p.id = l.provider_id
            WHERE r.job_id = ? ORDER BY p.name
            """)) {
            query.setLong(1, job);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) providers.computeIfAbsent(rows.getLong(1), unused -> new ArrayList<>())
                    .add(rows.getString(2));
            }
        }
        List<CompletedCrafting.Resource> resources = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement(descriptionsSql() + """
             AND NOT EXISTS (SELECT 1 FROM crafting_products p
                 WHERE p.crafting_resource_id = r.id AND p.job_id = r.job_id)
             ORDER BY r.ordinal
            """)) {
            query.setLong(1, job);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong("id");
                    resources.add(
                        new CompletedCrafting.Resource(
                            snapshot(rows),
                            rows.getLong("time_spent"),
                            intervals.getOrDefault(id, Collections.emptyList()),
                            providers.getOrDefault(id, Collections.emptyList())));
                }
            }
        }
        return resources;
    }

    private static List<CompletedCrafting.Provider> providers(Connection db, long job) throws SQLException {
        Map<Long, List<CompletedCrafting.Interval>> intervals = readIntervals(
            db,
            "crafting_provider_intervals",
            "provider_id",
            "crafting_providers",
            job);
        Map<Long, List<DimensionalCoords>> locations = new HashMap<>();
        try (PreparedStatement query = db.prepareStatement("""
            SELECT l.provider_id, l.dimension_id, l.x, l.y, l.z FROM crafting_provider_locations l
            JOIN crafting_providers p ON p.id = l.provider_id WHERE p.job_id = ? ORDER BY l.ordinal
            """)) {
            query.setLong(1, job);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) locations.computeIfAbsent(rows.getLong(1), unused -> new ArrayList<>())
                    .add(new DimensionalCoords(rows.getString(2), rows.getInt(3), rows.getInt(4), rows.getInt(5)));
            }
        }
        List<CompletedCrafting.Provider> providers = new ArrayList<>();
        try (PreparedStatement query = db
            .prepareStatement("SELECT id, name FROM crafting_providers WHERE job_id = ? ORDER BY ordinal")) {
            query.setLong(1, job);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) providers.add(
                    new CompletedCrafting.Provider(
                        rows.getString(2),
                        locations.getOrDefault(rows.getLong(1), Collections.emptyList()),
                        intervals.getOrDefault(rows.getLong(1), Collections.emptyList())));
            }
        }
        return providers;
    }

    private static Map<Long, List<CompletedCrafting.Interval>> readIntervals(Connection db, String table, String owner,
        String owners, long job) throws SQLException {
        Map<Long, List<CompletedCrafting.Interval>> result = new HashMap<>();
        try (PreparedStatement query = db.prepareStatement(
            "SELECT i." + owner
                + ", i.started, i.ended FROM "
                + table
                + " i JOIN "
                + owners
                + " o ON o.id = i."
                + owner
                + " WHERE o.job_id = ? ORDER BY i.ordinal")) {
            query.setLong(1, job);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.computeIfAbsent(rows.getLong(1), unused -> new ArrayList<>())
                    .add(new CompletedCrafting.Interval(rows.getLong(2), rows.getLong(3)));
            }
        }
        return result;
    }

    public void maintain() throws IOException {
        database.write(db -> {
            CraftingHistory policy = database.craftingPolicy();
            WorldGridStore.normalizeCraftingPolicy(db, policy);
            List<Long> grids = new ArrayList<>();
            try (Statement query = db.createStatement();
                ResultSet rows = query.executeQuery("SELECT DISTINCT grid_id FROM crafting_jobs")) {
                while (rows.next()) grids.add(rows.getLong(1));
            }
            long now = System.currentTimeMillis();
            for (Long grid : grids) prune(db, grid, now, policy.historyMaxJobsPerGrid);
            return null;
        });
    }

    private static void prune(Connection db, long grid, long now, int maxJobs) throws SQLException {
        long expiry;
        try (PreparedStatement query = db.prepareStatement("SELECT crafting_retention_days FROM grids WHERE id = ?")) {
            query.setLong(1, grid);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                expiry = now - TimeUnit.DAYS.toMillis(rows.getInt(1));
            }
        }
        List<Long> removed = new ArrayList<>();
        try (PreparedStatement query = db
            .prepareStatement("SELECT id, time_done FROM crafting_jobs WHERE grid_id = ? ORDER BY id DESC")) {
            query.setLong(1, grid);
            int retained = 0;
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (rows.getLong(2) < expiry || retained >= maxJobs) removed.add(rows.getLong(1));
                    else retained++;
                }
            }
        }
        Set<Long> resources = new HashSet<>();
        try (
            PreparedStatement query = db.prepareStatement(
                "SELECT resource_id FROM crafting_resources WHERE job_id = ? AND resource_id IS NOT NULL");
            PreparedStatement delete = db.prepareStatement("DELETE FROM crafting_jobs WHERE id = ?")) {
            for (Long job : removed) {
                query.setLong(1, job);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) resources.add(rows.getLong(1));
                }
                delete.setLong(1, job);
                delete.addBatch();
            }
            if (!removed.isEmpty()) delete.executeBatch();
        }
        ResourceCatalogue.removeUnreferenced(db, resources);
    }

    private static long gridId(Connection db, StableKey grid) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("SELECT id FROM grids WHERE grid_key = ?")) {
            query.setString(1, grid.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1;
            }
        }
    }
}
