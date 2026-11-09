package pl.kuba6000.ae2webintegration.core.persistence;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

/** Current schema and explicitly supported upgrades; all DDL shares the world startup transaction. */
final class WorldSchema {

    private static final int VERSION = 10;
    private static final List<DatabaseMigration> MIGRATIONS = Collections.singletonList(new HomePinGroupsMigration());
    private static final Set<String> TABLES = new HashSet<>(
        Arrays.asList(
            "world_schema",
            "grids",
            "history_resources",
            "grid_controllers",
            "history_event_ids",
            "history_measurements",
            "history_stock",
            "history_flow",
            "history_coverage",
            "resource_pins",
            "home_pin_groups",
            "crafting_jobs",
            "crafting_requesters",
            "crafting_resources",
            "crafting_products",
            "crafting_resource_descriptions",
            "crafting_providers",
            "crafting_provider_locations",
            "crafting_resource_providers",
            "crafting_resource_intervals",
            "crafting_provider_intervals"));

    private static final Set<String> PIN_COLUMNS = new HashSet<>(
        Arrays.asList(
            "pin_key",
            "owner",
            "grid_id",
            "resource_id",
            "terminal_order",
            "home_order",
            "show_stock",
            "show_flow",
            "show_chart",
            "series_stock",
            "series_input",
            "series_output",
            "series_net",
            "range_millis",
            "show_recent_flow",
            "flow_range_millis"));

    private WorldSchema() {}

    /** Validate before changing journal mode or making any other persistent modification. */
    static void validate(@NotNull Connection db) throws SQLException, IOException {
        inspect(db);
    }

    private static int inspect(@NotNull Connection db) throws SQLException, IOException {
        Set<String> existing = new HashSet<>();
        try (Statement sql = db.createStatement(); ResultSet objects = sql.executeQuery("""
            SELECT name, type FROM sqlite_master
            WHERE type IN ('table', 'view', 'trigger') AND name NOT GLOB 'sqlite_*'
            """)) {
            while (objects.next()) {
                if (!"table".equals(objects.getString(2)))
                    throw new IOException("World database contains unknown objects");
                existing.add(objects.getString(1));
            }
        }
        if (existing.isEmpty()) return 0;
        if (!existing.contains("world_schema")) throw new IOException("World database schema is unrecognized");
        long version;
        try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery("""
            SELECT singleton, version, identities_imported,
                typeof(singleton) = 'integer' AND typeof(version) = 'integer'
                    AND typeof(identities_imported) = 'integer' AND identities_imported IN (0, 1)
            FROM world_schema
            """)) {
            if (!rows.next() || rows.getLong(1) != 1 || !rows.getBoolean(4))
                throw new IOException("Unsupported world database schema metadata");
            version = rows.getLong(2);
            if (rows.next()) throw new IOException("Unsupported world database schema metadata");
        }
        if (version != 9 && version != VERSION) throw new IOException("Unsupported world database schema version");
        int source = (int) version;
        Set<String> tables = new HashSet<>(TABLES);
        Set<String> pinColumns = new HashSet<>(PIN_COLUMNS);
        if (source == 9) tables.remove("home_pin_groups");
        else {
            pinColumns.add("home_group_key");
            requireColumns(
                db,
                "home_pin_groups",
                new HashSet<>(Arrays.asList("group_key", "owner", "name", "ordinal")));
        }
        if (!existing.equals(tables)) throw new IOException("World database schema is incomplete or unrecognized");
        requireColumns(db, "resource_pins", pinColumns);
        for (int step = source; step != VERSION;) step = migrationFrom(step).toVersion();
        return source;
    }

    private static void requireColumns(Connection db, String table, Set<String> expected)
        throws SQLException, IOException {
        Set<String> columns = new HashSet<>();
        try (Statement sql = db.createStatement();
            ResultSet rows = sql.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) columns.add(rows.getString("name"));
        }
        if (!columns.equals(expected)) throw new IOException("Unrecognized world database table: " + table);
    }

    private static DatabaseMigration migrationFrom(int version) throws IOException {
        DatabaseMigration selected = null;
        for (DatabaseMigration migration : MIGRATIONS) {
            if (migration.fromVersion() != version) continue;
            if (selected != null || migration.toVersion() <= version || migration.toVersion() > VERSION)
                throw new IOException("Invalid world database migration registration");
            selected = migration;
        }
        if (selected == null) throw new IOException("Unsupported world database schema version");
        return selected;
    }

    static void initialize(@NotNull Connection db) throws SQLException, IOException {
        int source = inspect(db);
        if (source != 0) {
            while (source != VERSION) {
                DatabaseMigration migration = migrationFrom(source);
                migration.migrate(db);
                try (PreparedStatement update = db
                    .prepareStatement("UPDATE world_schema SET version = ? WHERE singleton = 1 AND version = ?")) {
                    update.setInt(1, migration.toVersion());
                    update.setInt(2, source);
                    if (update.executeUpdate() != 1)
                        throw new IOException("World database schema changed during upgrade");
                }
                source = migration.toVersion();
            }
            validate(db);
            return;
        }
        try (Statement sql = db.createStatement()) {
            sql.execute("""
                CREATE TABLE world_schema (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    version INTEGER NOT NULL CHECK (typeof(version) = 'integer'),
                    identities_imported INTEGER NOT NULL CHECK (identities_imported IN (0, 1))
                )
                """);
            sql.execute("INSERT INTO world_schema VALUES (1, " + VERSION + ", 0)");
            sql.execute(
                """
                    CREATE TABLE grids (
                        id INTEGER PRIMARY KEY,
                        grid_key TEXT NOT NULL CHECK (typeof(grid_key) = 'text' AND length(grid_key) <= 22) UNIQUE,
                        row_count INTEGER NOT NULL CHECK (typeof(row_count) = 'integer'),
                        deleted_until INTEGER NOT NULL CHECK (typeof(deleted_until) = 'integer'),
                        first_observed INTEGER NOT NULL CHECK (typeof(first_observed) = 'integer'),
                        retention_days INTEGER NOT NULL CHECK (typeof(retention_days) = 'integer' AND retention_days BETWEEN -2147483648 AND 2147483647),
                        crafting_retention_days INTEGER NOT NULL CHECK (typeof(crafting_retention_days) = 'integer' AND crafting_retention_days BETWEEN -2147483648 AND 2147483647),
                        name TEXT NOT NULL CHECK (typeof(name) = 'text' AND length(name) <= 128),
                        crafting_tracked INTEGER NOT NULL CHECK (crafting_tracked IN (0, 1)),
                        machine_crafting_tracked INTEGER NOT NULL CHECK (machine_crafting_tracked IN (0, 1)),
                        stock_tracked INTEGER NOT NULL CHECK (stock_tracked IN (0, 1)),
                        flow_tracked INTEGER NOT NULL CHECK (flow_tracked IN (0, 1)),
                        stock_seconds INTEGER NOT NULL CHECK (typeof(stock_seconds) = 'integer' AND stock_seconds BETWEEN -2147483648 AND 2147483647),
                        flow_seconds INTEGER NOT NULL CHECK (typeof(flow_seconds) = 'integer' AND flow_seconds BETWEEN -2147483648 AND 2147483647)
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE history_resources (
                        id INTEGER PRIMARY KEY,
                        item_key TEXT NOT NULL CHECK (typeof(item_key) = 'text' AND length(item_key) <= 22) UNIQUE,
                        registry_namespace TEXT CHECK (registry_namespace IS NULL OR (typeof(registry_namespace) = 'text' AND length(registry_namespace) <= 4096)),
                        registry_path TEXT CHECK (registry_path IS NULL OR (typeof(registry_path) = 'text' AND length(registry_path) <= 16384)),
                        components INTEGER NOT NULL CHECK (typeof(components) = 'integer' AND components BETWEEN -2147483648 AND 2147483647),
                        damage INTEGER NOT NULL CHECK (typeof(damage) = 'integer' AND damage BETWEEN -2147483648 AND 2147483647),
                        display_name TEXT NOT NULL CHECK (typeof(display_name) = 'text' AND length(display_name) <= 1048576),
                        resource_type TEXT NOT NULL CHECK (typeof(resource_type) = 'text' AND length(resource_type) <= 16),
                        base_key TEXT CHECK (base_key IS NULL OR (typeof(base_key) = 'text' AND length(base_key) <= 22))
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE grid_controllers (
                        grid_id INTEGER NOT NULL CHECK (typeof(grid_id) = 'integer') REFERENCES grids(id),
                        dimension_id TEXT NOT NULL CHECK (typeof(dimension_id) = 'text' AND length(dimension_id) <= 4096),
                        x INTEGER NOT NULL CHECK (typeof(x) = 'integer' AND x BETWEEN -2147483648 AND 2147483647), y INTEGER NOT NULL CHECK (typeof(y) = 'integer' AND y BETWEEN -2147483648 AND 2147483647), z INTEGER NOT NULL CHECK (typeof(z) = 'integer' AND z BETWEEN -2147483648 AND 2147483647),
                        PRIMARY KEY (dimension_id, x, y, z)
                    )
                    """);
            sql.execute("""
                CREATE TABLE history_event_ids (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    last_id INTEGER NOT NULL CHECK (typeof(last_id) = 'integer' AND last_id >= 0)
                )
                """);
            sql.execute("INSERT INTO history_event_ids VALUES (1, 0)");
            sql.execute("""
                CREATE TABLE history_measurements (
                    id INTEGER PRIMARY KEY,
                    grid_id INTEGER NOT NULL CHECK (typeof(grid_id) = 'integer') REFERENCES grids(id),
                    observed_at INTEGER NOT NULL CHECK (typeof(observed_at) = 'integer'),
                    generation INTEGER NOT NULL CHECK (typeof(generation) = 'integer'),
                    flow_from INTEGER CHECK (flow_from IS NULL OR (typeof(flow_from) = 'integer'))
                )
                """);
            sql.execute(
                """
                    CREATE TABLE history_stock (
                        id INTEGER PRIMARY KEY,
                        measurement_id INTEGER NOT NULL CHECK (typeof(measurement_id) = 'integer') REFERENCES history_measurements(id),
                        resource_id INTEGER NOT NULL CHECK (typeof(resource_id) = 'integer') REFERENCES history_resources(id),
                        quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer')
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE history_flow (
                        id INTEGER PRIMARY KEY,
                        measurement_id INTEGER NOT NULL CHECK (typeof(measurement_id) = 'integer') REFERENCES history_measurements(id),
                        resource_id INTEGER NOT NULL CHECK (typeof(resource_id) = 'integer') REFERENCES history_resources(id),
                        input_amount INTEGER NOT NULL CHECK (typeof(input_amount) = 'integer'),
                        output_amount INTEGER NOT NULL CHECK (typeof(output_amount) = 'integer'),
                        input_operations INTEGER NOT NULL CHECK (typeof(input_operations) = 'integer'),
                        output_operations INTEGER NOT NULL CHECK (typeof(output_operations) = 'integer')
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE history_coverage (
                        id INTEGER PRIMARY KEY,
                        measurement_id INTEGER NOT NULL CHECK (typeof(measurement_id) = 'integer') REFERENCES history_measurements(id),
                        kind TEXT NOT NULL CHECK (typeof(kind) = 'text' AND length(kind) <= 16) CHECK (kind IN ('GAP', 'OBSERVATION', 'FLOW_OBSERVATION'))
                    )
                    """);
            HomePinGroupsMigration.createGroupTable(sql);
            sql.execute(
                """
                    CREATE TABLE resource_pins (
                        pin_key TEXT NOT NULL CHECK (typeof(pin_key) = 'text' AND length(pin_key) <= 22) PRIMARY KEY,
                        owner TEXT NOT NULL CHECK (typeof(owner) = 'text' AND length(owner) <= 36),
                        grid_id INTEGER NOT NULL CHECK (typeof(grid_id) = 'integer') REFERENCES grids(id),
                        resource_id INTEGER NOT NULL CHECK (typeof(resource_id) = 'integer') REFERENCES history_resources(id),
                        terminal_order INTEGER CHECK (terminal_order IS NULL OR (typeof(terminal_order) = 'integer' AND terminal_order BETWEEN 0 AND 2147483647)),
                        home_order INTEGER CHECK (home_order IS NULL OR (typeof(home_order) = 'integer' AND home_order BETWEEN 0 AND 2147483647)),
                        show_stock INTEGER NOT NULL CHECK (show_stock IN (0, 1)), show_flow INTEGER NOT NULL CHECK (show_flow IN (0, 1)), show_chart INTEGER NOT NULL CHECK (show_chart IN (0, 1)),
                        series_stock INTEGER NOT NULL CHECK (series_stock IN (0, 1)), series_input INTEGER NOT NULL CHECK (series_input IN (0, 1)),
                        series_output INTEGER NOT NULL CHECK (series_output IN (0, 1)), series_net INTEGER NOT NULL CHECK (series_net IN (0, 1)),
                        range_millis INTEGER NOT NULL CHECK (typeof(range_millis) = 'integer'),
                        show_recent_flow INTEGER NOT NULL CHECK (show_recent_flow IN (0, 1)),
                        flow_range_millis INTEGER NOT NULL CHECK (typeof(flow_range_millis) = 'integer'),
                        home_group_key TEXT REFERENCES home_pin_groups(group_key)
                            CHECK (home_group_key IS NULL OR (typeof(home_group_key) = 'text' AND length(home_group_key) <= 22 AND home_order IS NOT NULL)),
                        UNIQUE (owner, grid_id, resource_id),
                        CHECK (terminal_order IS NOT NULL OR home_order IS NOT NULL)
                    )
                    """);
            sql.execute(
                "CREATE INDEX history_measurements_grid_time ON history_measurements(grid_id, observed_at, id)");
            for (String name : new String[] { "history_stock", "history_flow", "history_coverage" }) {
                String field = name.equals("history_coverage") ? "kind" : "resource_id";
                sql.execute(
                    "CREATE INDEX " + name + "_measurement_item ON " + name + "(measurement_id, " + field + ", id)");
            }
            sql.execute("""
                CREATE TABLE crafting_jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    grid_id INTEGER NOT NULL CHECK (typeof(grid_id) = 'integer') REFERENCES grids(id),
                    time_started INTEGER NOT NULL CHECK (typeof(time_started) = 'integer'),
                    time_done INTEGER NOT NULL CHECK (typeof(time_done) = 'integer'),
                    cancelled INTEGER NOT NULL CHECK (cancelled IN (0, 1))
                )
                """);
            sql.execute("CREATE INDEX crafting_jobs_grid_id ON crafting_jobs(grid_id, id)");
            sql.execute(
                """
                    CREATE TABLE crafting_requesters (
                        job_id INTEGER NOT NULL REFERENCES crafting_jobs(id) ON DELETE CASCADE,
                        ordinal INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal BETWEEN 0 AND 2147483647),
                        source TEXT NOT NULL CHECK (source IN ('PLAYER', 'AUTOMATION', 'WEB')),
                        player_uuid TEXT CHECK (player_uuid IS NULL OR (typeof(player_uuid) = 'text' AND length(player_uuid) = 36)),
                        player_name TEXT CHECK (player_name IS NULL OR (typeof(player_name) = 'text' AND length(player_name) <= 1048576)),
                        CHECK ((source = 'PLAYER' AND player_uuid IS NOT NULL AND player_name IS NOT NULL)
                            OR (source <> 'PLAYER' AND player_uuid IS NULL AND player_name IS NULL)),
                        PRIMARY KEY (job_id, ordinal)
                    )
                    """);
            sql.execute(
                "CREATE UNIQUE INDEX crafting_requester_players ON crafting_requesters(job_id, player_uuid) WHERE source = 'PLAYER'");
            sql.execute(
                "CREATE UNIQUE INDEX crafting_requester_sources ON crafting_requesters(job_id, source) WHERE source <> 'PLAYER'");
            sql.execute(
                """
                    CREATE TABLE crafting_resources (
                        id INTEGER PRIMARY KEY,
                        job_id INTEGER NOT NULL CHECK (typeof(job_id) = 'integer') REFERENCES crafting_jobs(id) ON DELETE CASCADE,
                        resource_id INTEGER CHECK (resource_id IS NULL OR (typeof(resource_id) = 'integer')) REFERENCES history_resources(id),
                        ordinal INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal BETWEEN 0 AND 2147483647),
                        quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity >= 0),
                        time_spent INTEGER NOT NULL CHECK (typeof(time_spent) = 'integer'),
                        UNIQUE (job_id, ordinal),
                        UNIQUE (job_id, id)
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE crafting_products (
                        job_id INTEGER PRIMARY KEY,
                        crafting_resource_id INTEGER NOT NULL CHECK (typeof(crafting_resource_id) = 'integer'),
                        FOREIGN KEY (job_id, crafting_resource_id) REFERENCES crafting_resources(job_id, id) ON DELETE CASCADE
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE crafting_resource_descriptions (
                        crafting_resource_id INTEGER PRIMARY KEY REFERENCES crafting_resources(id) ON DELETE CASCADE,
                        registry_namespace TEXT CHECK (registry_namespace IS NULL OR (typeof(registry_namespace) = 'text' AND length(registry_namespace) <= 4096)), registry_path TEXT CHECK (registry_path IS NULL OR (typeof(registry_path) = 'text' AND length(registry_path) <= 16384)),
                        components INTEGER NOT NULL CHECK (typeof(components) = 'integer' AND components BETWEEN -2147483648 AND 2147483647), damage INTEGER NOT NULL CHECK (typeof(damage) = 'integer' AND damage BETWEEN -2147483648 AND 2147483647),
                        display_name TEXT NOT NULL CHECK (typeof(display_name) = 'text' AND length(display_name) <= 1048576), resource_type TEXT NOT NULL CHECK (typeof(resource_type) = 'text' AND length(resource_type) <= 16)
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE crafting_providers (
                        id INTEGER PRIMARY KEY,
                        job_id INTEGER NOT NULL CHECK (typeof(job_id) = 'integer') REFERENCES crafting_jobs(id) ON DELETE CASCADE,
                        ordinal INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal BETWEEN 0 AND 2147483647), name TEXT NOT NULL CHECK (typeof(name) = 'text' AND length(name) <= 1048576),
                        UNIQUE (job_id, ordinal),
                        UNIQUE (job_id, id)
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE crafting_provider_locations (
                        provider_id INTEGER NOT NULL CHECK (typeof(provider_id) = 'integer') REFERENCES crafting_providers(id) ON DELETE CASCADE,
                        ordinal INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal BETWEEN 0 AND 2147483647),
                        dimension_id TEXT NOT NULL CHECK (typeof(dimension_id) = 'text' AND length(dimension_id) <= 4096),
                        x INTEGER NOT NULL CHECK (typeof(x) = 'integer' AND x BETWEEN -2147483648 AND 2147483647), y INTEGER NOT NULL CHECK (typeof(y) = 'integer' AND y BETWEEN -2147483648 AND 2147483647), z INTEGER NOT NULL CHECK (typeof(z) = 'integer' AND z BETWEEN -2147483648 AND 2147483647),
                        PRIMARY KEY (provider_id, ordinal)
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE crafting_resource_providers (
                        job_id INTEGER NOT NULL CHECK (typeof(job_id) = 'integer'),
                        crafting_resource_id INTEGER NOT NULL CHECK (typeof(crafting_resource_id) = 'integer'),
                        provider_id INTEGER NOT NULL CHECK (typeof(provider_id) = 'integer'),
                        PRIMARY KEY (crafting_resource_id, provider_id),
                        FOREIGN KEY (job_id, crafting_resource_id) REFERENCES crafting_resources(job_id, id) ON DELETE CASCADE,
                        FOREIGN KEY (job_id, provider_id) REFERENCES crafting_providers(job_id, id) ON DELETE CASCADE
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE crafting_resource_intervals (
                        crafting_resource_id INTEGER NOT NULL CHECK (typeof(crafting_resource_id) = 'integer') REFERENCES crafting_resources(id) ON DELETE CASCADE,
                        ordinal INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal BETWEEN 0 AND 2147483647), started INTEGER NOT NULL CHECK (typeof(started) = 'integer'), ended INTEGER NOT NULL CHECK (typeof(ended) = 'integer'),
                        PRIMARY KEY (crafting_resource_id, ordinal)
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE crafting_provider_intervals (
                        provider_id INTEGER NOT NULL CHECK (typeof(provider_id) = 'integer') REFERENCES crafting_providers(id) ON DELETE CASCADE,
                        ordinal INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal BETWEEN 0 AND 2147483647), started INTEGER NOT NULL CHECK (typeof(started) = 'integer'), ended INTEGER NOT NULL CHECK (typeof(ended) = 'integer'),
                        PRIMARY KEY (provider_id, ordinal)
                    )
                    """);
            sql.execute("CREATE INDEX grid_controllers_grid ON grid_controllers(grid_id)");
            sql.execute("CREATE INDEX history_stock_resource ON history_stock(resource_id, measurement_id, id)");
            sql.execute("CREATE INDEX history_flow_resource ON history_flow(resource_id, measurement_id, id)");
            sql.execute("CREATE INDEX resource_pins_grid ON resource_pins(grid_id)");
            sql.execute("CREATE INDEX resource_pins_resource ON resource_pins(resource_id)");
            sql.execute(HomePinGroupsMigration.GROUP_INDEX);
            sql.execute("CREATE INDEX crafting_resources_resource ON crafting_resources(resource_id)");
            sql.execute(
                "CREATE INDEX crafting_resource_providers_resource ON crafting_resource_providers(job_id, crafting_resource_id)");
            sql.execute(
                "CREATE INDEX crafting_resource_providers_provider ON crafting_resource_providers(job_id, provider_id)");
        }
    }
}
