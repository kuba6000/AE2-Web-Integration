package pl.kuba6000.ae2webintegration.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;

class WorldSchemaMigrationTest {

    private static final String SCHEMA_TEN_REQUESTERS_WITHOUT_ORDERS = "1:0:PLAYER:11111111-2222-3333-4444-555555555555:Original player"
        + "|1:1:AUTOMATION:-:-"
        + "|2:0:WEB:-:-";
    private static final String SCHEMA_TEN_REQUESTERS = "1:0:PLAYER:11111111-2222-3333-4444-555555555555:Original player:-:-"
        + "|1:1:AUTOMATION:-:-:-:-"
        + "|2:0:WEB:-:-:-:-";

    @TempDir
    Path directory;

    @Test
    void openingReleasedSchemaNinePreservesPinsHistoryAndSettingsAcrossReopen() throws Exception {
        Path file = directory.resolve("released.sqlite");
        createReleasedDatabase(file, 9, false);
        for (int opening = 0; opening < 2; opening++) {
            try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
                database.open();
                database.read(db -> {
                    assertScalar(db, "SELECT version FROM world_schema", "11");
                    assertScalar(db, "SELECT identities_imported FROM world_schema", "1");
                    assertScalar(
                        db,
                        "SELECT name || ':' || stock_seconds || ':' || flow_seconds FROM grids",
                        "Preserved:600:120");
                    assertScalar(db, "SELECT quantity FROM history_stock", "987");
                    assertScalar(db, "SELECT input_amount || ':' || output_amount FROM history_flow", "80:15");
                    assertScalar(db, "SELECT player_name FROM crafting_requesters", "Original player");
                    assertScalar(
                        db,
                        "SELECT pin_key || ':' || terminal_order || ':' || home_order || ':' || range_millis FROM resource_pins",
                        "AgICAgICAgICAgICAgICAg:2:4:3600000");
                    assertScalar(db, "SELECT home_group_key IS NULL FROM resource_pins", "1");
                    assertScalar(db, "SELECT count(*) FROM home_pin_groups", "0");
                    assertScalar(db, "SELECT standing_orders_enabled FROM grids", "1");
                    assertScalar(db, "SELECT count(*) FROM standing_orders", "0");
                    return null;
                });
            }
        }
    }

    @Test
    void openingReleasedSchemaTenPreservesDataAndEnablesStandingOrdersForExistingGrids() throws Exception {
        Path file = directory.resolve("released-ten.sqlite");
        createReleasedDatabase(file, 10, false);
        for (int opening = 0; opening < 2; opening++) {
            try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
                database.open();
                database.read(db -> {
                    assertScalar(db, "SELECT version FROM world_schema", "11");
                    assertScalar(
                        db,
                        "SELECT name || ':' || stock_seconds || ':' || flow_seconds || ':' || standing_orders_enabled FROM grids",
                        "Preserved:600:120:1");
                    assertScalar(db, "SELECT quantity FROM history_stock", "987");
                    assertScalar(db, "SELECT count(*) FROM crafting_jobs", "2");
                    assertScalar(db, requesters(true), SCHEMA_TEN_REQUESTERS);
                    assertScalar(
                        db,
                        "SELECT pin_key || ':' || home_group_key FROM resource_pins",
                        "AgICAgICAgICAgICAgICAg:BAQEBAQEBAQEBAQEBAQEBA");
                    assertScalar(db, "SELECT count(*) FROM standing_orders", "0");
                    assertScalar(db, "SELECT count(*) FROM standing_order_resources", "0");
                    return null;
                });
            }
        }
    }

    @Test
    void migratedSchemaTenIsEquivalentToAFreshSchemaEleven() throws Exception {
        Path migrated = directory.resolve("migrated.sqlite");
        createReleasedDatabase(migrated, 10, false);
        try (WorldDatabase database = new WorldDatabase(migrated, new ConfigSettings.ResourceHistory())) {
            database.open();
        }
        Path fresh = directory.resolve("fresh.sqlite");
        try (WorldDatabase database = new WorldDatabase(fresh, new ConfigSettings.ResourceHistory())) {
            database.open();
        }
        try (Connection migratedDb = connect(migrated); Connection freshDb = connect(fresh)) {
            assertEquals(describe(freshDb), describe(migratedDb));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void craftingRequestersAdmitStandingOrdersAndRejectInconsistentRows(boolean migrated) throws Exception {
        Path file = directory.resolve("requesters.sqlite");
        if (migrated) createReleasedDatabase(file, 10, false);
        try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
            database.open();
            execute(
                database,
                "INSERT INTO grids (id, grid_key, row_count, deleted_until, first_observed, retention_days, crafting_retention_days, name, "
                    + "crafting_tracked, machine_crafting_tracked, stock_tracked, flow_tracked, stock_seconds, flow_seconds) "
                    + "VALUES (50, 'BBBBBBBBBBBBBBBBBBBBBB', 0, 0, 0, 7, 7, 'Other', 0, 0, 0, 0, 300, 60)",
                "INSERT INTO crafting_jobs (id, grid_id, time_started, time_done, cancelled) VALUES (50, 50, 1, 2, 0)");
            execute(
                database,
                requester(0, "'STANDING_ORDER'", "NULL", "NULL", "'AAAAAAAAAAAAAAAAAAAAAA'", "'Keep plates'"),
                requester(1, "'STANDING_ORDER'", "NULL", "NULL", "'AQEBAQEBAQEBAQEBAQEBAQ'", "'Keep ingots'"),
                requester(2, "'WEB'", "NULL", "NULL", "NULL", "NULL"),
                requester(3, "'AUTOMATION'", "NULL", "NULL", "NULL", "NULL"),
                requester(4, "'PLAYER'", "'11111111-2222-3333-4444-555555555555'", "'Player'", "NULL", "NULL"));
            String[] rejected = {
                requester(5, "'STANDING_ORDER'", "NULL", "NULL", "'AAAAAAAAAAAAAAAAAAAAAA'", "'Same order again'"),
                requester(5, "'WEB'", "NULL", "NULL", "NULL", "NULL"),
                requester(5, "'AUTOMATION'", "NULL", "NULL", "NULL", "NULL"),
                requester(5, "'PLAYER'", "'11111111-2222-3333-4444-555555555555'", "'Player'", "NULL", "NULL"),
                requester(5, "'STANDING_ORDER'", "NULL", "NULL", "NULL", "'No key'"),
                requester(5, "'STANDING_ORDER'", "NULL", "NULL", "'CQkJCQkJCQkJCQkJCQkJCQ'", "NULL"),
                requester(
                    5,
                    "'STANDING_ORDER'",
                    "'11111111-2222-3333-4444-555555555555'",
                    "'Player'",
                    "'CQkJCQkJCQkJCQkJCQkJCQ'",
                    "'With player'"),
                requester(
                    5,
                    "'STANDING_ORDER'",
                    "NULL",
                    "NULL",
                    "'CQkJCQkJCQkJCQkJCQkJCQ'",
                    "replace(hex(zeroblob(129)), '00', 'n')"),
                requester(5, "'WEB'", "NULL", "NULL", "'CQkJCQkJCQkJCQkJCQkJCQ'", "NULL"),
                requester(5, "'AUTOMATION'", "NULL", "NULL", "NULL", "'Name'"),
                requester(
                    5,
                    "'PLAYER'",
                    "'11111111-2222-3333-4444-555555555555'",
                    "'Player'",
                    "'CQkJCQkJCQkJCQkJCQkJCQ'",
                    "NULL"),
                requester(5, "'OTHER'", "NULL", "NULL", "NULL", "NULL") };
            for (String statement : rejected) assertThrows(IOException.class, () -> execute(database, statement));
            database.read(db -> {
                assertScalar(db, "SELECT count(*) FROM crafting_requesters WHERE job_id = 50", "5");
                return null;
            });
        }
    }

    @Test
    void failedUpgradeRollsBackNewObjectsAndVersionBeforeRetry() throws Exception {
        Path file = directory.resolve("failed.sqlite");
        createReleasedDatabase(file, 9, true);
        try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
            assertThrows(IOException.class, database::open);
            assertThrows(IOException.class, database::open);
        }
        try (Connection db = connect(file)) {
            assertScalar(db, "SELECT version FROM world_schema", "9");
            assertScalar(db, "SELECT count(*) FROM sqlite_master WHERE name = 'home_pin_groups'", "0");
            assertScalar(
                db,
                "SELECT count(*) FROM pragma_table_info('resource_pins') WHERE name = 'home_group_key'",
                "0");
            assertScalar(db, "SELECT home_order FROM resource_pins", "4");
        }
    }

    @Test
    void failedStandingOrdersUpgradeRollsBackEveryChangeBeforeRetry() throws Exception {
        Path file = directory.resolve("failed-ten.sqlite");
        createReleasedDatabase(file, 10, true);
        try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
            assertThrows(IOException.class, database::open);
            assertThrows(IOException.class, database::open);
        }
        try (Connection db = connect(file)) {
            assertScalar(db, "SELECT version FROM world_schema", "10");
            assertScalar(
                db,
                "SELECT count(*) FROM sqlite_master WHERE name IN ('standing_orders', 'standing_order_resources', 'crafting_requesters_v10')",
                "0");
            assertScalar(
                db,
                "SELECT count(*) FROM pragma_table_info('grids') WHERE name = 'standing_orders_enabled'",
                "0");
            assertScalar(db, "SELECT count(*) FROM pragma_table_info('crafting_requesters')", "5");
            assertScalar(db, requesters(false), SCHEMA_TEN_REQUESTERS_WITHOUT_ORDERS);
            assertScalar(
                db,
                "SELECT sql FROM sqlite_master WHERE name = 'crafting_requester_sources'",
                "CREATE UNIQUE INDEX crafting_requester_sources ON crafting_requesters(job_id, source) WHERE source <> 'PLAYER'");
        }
    }

    @Test
    void schemaVersionControlsTheUpgradeWithoutRejectingAdditionalColumns() throws Exception {
        Path file = directory.resolve("extended.sqlite");
        createReleasedDatabase(file, 9, false);
        try (Connection db = connect(file); Statement sql = db.createStatement()) {
            sql.execute("ALTER TABLE resource_pins ADD COLUMN extension TEXT");
            sql.execute("UPDATE resource_pins SET extension = 'Preserved extension'");
        }
        try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
            database.open();
            database.read(db -> {
                assertScalar(db, "SELECT version FROM world_schema", "11");
                assertScalar(db, "SELECT extension FROM resource_pins", "Preserved extension");
                assertScalar(db, "SELECT home_group_key IS NULL FROM resource_pins", "1");
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void missingRequiredTableAbortsLoadingWithoutCommittingAnUpgrade(boolean upgrade) throws Exception {
        Path file = directory.resolve("missing-table.sqlite");
        if (upgrade) createReleasedDatabase(file, 9, false);
        else {
            try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
                database.open();
            }
        }
        try (Connection db = connect(file); Statement sql = db.createStatement()) {
            sql.execute("DROP TABLE crafting_provider_intervals");
        }
        try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
            assertThrows(IOException.class, database::open);
        }
        try (Connection db = connect(file)) {
            assertScalar(db, "SELECT version FROM world_schema", upgrade ? "9" : "11");
            assertScalar(db, "SELECT count(*) FROM sqlite_master WHERE name = 'crafting_provider_intervals'", "0");
            if (upgrade) {
                assertScalar(db, "SELECT count(*) FROM sqlite_master WHERE name = 'home_pin_groups'", "0");
                assertScalar(db, "SELECT count(*) FROM sqlite_master WHERE name = 'standing_orders'", "0");
                assertScalar(
                    db,
                    "SELECT count(*) FROM pragma_table_info('resource_pins') WHERE name = 'home_group_key'",
                    "0");
            }
        }
    }

    private static String requesters(boolean withOrders) {
        return "SELECT group_concat(job_id || ':' || ordinal || ':' || source || ':' || COALESCE(player_uuid, '-') || ':' || COALESCE(player_name, '-')"
            + (withOrders ? " || ':' || COALESCE(order_key, '-') || ':' || COALESCE(order_name, '-')" : "")
            + ", '|') FROM (SELECT * FROM crafting_requesters ORDER BY job_id, ordinal)";
    }

    private static String requester(int ordinal, String source, String uuid, String name, String orderKey,
        String orderName) {
        return "INSERT INTO crafting_requesters (job_id, ordinal, source, player_uuid, player_name, order_key, order_name) VALUES (50, "
            + ordinal
            + ", "
            + source
            + ", "
            + uuid
            + ", "
            + name
            + ", "
            + orderKey
            + ", "
            + orderName
            + ")";
    }

    private static void execute(WorldDatabase database, String... statements) throws IOException {
        database.write(db -> {
            try (Statement sql = db.createStatement()) {
                for (String statement : statements) sql.execute(statement);
            }
            return null;
        });
    }

    private static List<String> describe(Connection db) throws SQLException {
        List<String> lines = new ArrayList<>();
        for (String table : strings(
            db,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            lines.add("table " + table);
            lines.addAll(
                strings(
                    db,
                    "SELECT cid || '|' || name || '|' || type || '|' || \"notnull\" || '|' || COALESCE(dflt_value, '') || '|' || pk || '|' || hidden "
                        + "FROM pragma_table_xinfo('"
                        + table
                        + "') ORDER BY cid"));
            lines.addAll(
                strings(
                    db,
                    "SELECT id || '|' || seq || '|' || \"table\" || '|' || \"from\" || '|' || COALESCE(\"to\", '') || '|' || on_update || '|' || on_delete "
                        + "FROM pragma_foreign_key_list('"
                        + table
                        + "') ORDER BY id, seq"));
            // ALTER TABLE appends to the stored text, so only the column definitions identify the altered table.
            if (!table.equals("grids")) lines
                .addAll(strings(db, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'"));
        }
        lines.addAll(
            strings(
                db,
                "SELECT name || '|' || tbl_name || '|' || COALESCE(sql, '') FROM sqlite_master WHERE type = 'index' ORDER BY name"));
        lines.addAll(strings(db, "SELECT type || '|' || name FROM sqlite_master WHERE type IN ('view', 'trigger')"));
        return lines;
    }

    private static List<String> strings(Connection db, String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery(query)) {
            while (rows.next()) values.add(
                rows.getString(1)
                    .replaceAll("\\s+", " ")
                    .trim());
        }
        return values;
    }

    private static void createReleasedDatabase(Path file, int schema, boolean rejectNewVersion) throws Exception {
        String fixture;
        try (InputStream input = WorldSchemaMigrationTest.class
            .getResourceAsStream("/persistence/world-schema-" + schema + ".sql")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            fixture = new String(bytes.toByteArray(), UTF_8);
        }
        if (rejectNewVersion) fixture = fixture.replace(
            "CHECK (typeof(version) = 'integer')",
            "CHECK (typeof(version) = 'integer' AND version = " + schema + ")");
        try (Connection db = connect(file); Statement sql = db.createStatement()) {
            for (String statement : fixture.split(";")) if (!statement.trim()
                .isEmpty()) sql.execute(statement);
            sql.execute("UPDATE world_schema SET identities_imported = 1");
            sql.execute(
                "INSERT INTO grids VALUES (1, 'AAAAAAAAAAAAAAAAAAAAAA', 10, 0, 1234, 7, 7, 'Preserved', 1, 0, 1, 1, 600, 120)");
            sql.execute(
                "INSERT INTO history_resources VALUES (1, 'AQEBAQEBAQEBAQEBAQEBAQ', 'test', 'item', 0, 0, 'Item', 'ITEM', NULL)");
            sql.execute("INSERT INTO grid_controllers VALUES (1, 'test:world', 1, 2, 3)");
            sql.execute("INSERT INTO history_measurements VALUES (1, 1, 2000, 1, 1000)");
            sql.execute("INSERT INTO history_stock VALUES (1, 1, 1, 987)");
            sql.execute("INSERT INTO history_flow VALUES (2, 1, 1, 80, 15, 2, 1)");
            sql.execute("INSERT INTO history_coverage VALUES (3, 1, 'OBSERVATION')");
            sql.execute("INSERT INTO crafting_jobs VALUES (1, 1, 1000, 2000, 0)");
            sql.execute(
                "INSERT INTO crafting_requesters VALUES (1, 0, 'PLAYER', '11111111-2222-3333-4444-555555555555', 'Original player')");
            if (schema == 9) {
                sql.execute(
                    "INSERT INTO resource_pins VALUES ('AgICAgICAgICAgICAgICAg', 'admin', 1, 1, 2, 4, 1, 1, 0, 1, 1, 1, 0, 3600000, 1, 900000)");
                return;
            }
            sql.execute("INSERT INTO crafting_requesters VALUES (1, 1, 'AUTOMATION', NULL, NULL)");
            sql.execute("INSERT INTO crafting_jobs VALUES (2, 1, 3000, 4000, 1)");
            sql.execute("INSERT INTO crafting_requesters VALUES (2, 0, 'WEB', NULL, NULL)");
            sql.execute("INSERT INTO home_pin_groups VALUES ('BAQEBAQEBAQEBAQEBAQEBA', 'admin', 'Group', 0)");
            sql.execute(
                "INSERT INTO resource_pins VALUES ('AgICAgICAgICAgICAgICAg', 'admin', 1, 1, 2, 4, 1, 1, 0, 1, 1, 1, 0, 3600000, 1, 900000, 'BAQEBAQEBAQEBAQEBAQEBA')");
        }
    }

    private static void assertScalar(Connection db, String query, String expected) throws SQLException {
        try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery(query)) {
            assertTrue(rows.next());
            assertEquals(expected, rows.getString(1));
            assertFalse(rows.next());
        }
    }

    private static Connection connect(Path file) throws Exception {
        return new JDBC().connect("jdbc:sqlite:" + file.toUri(), new Properties());
    }
}
