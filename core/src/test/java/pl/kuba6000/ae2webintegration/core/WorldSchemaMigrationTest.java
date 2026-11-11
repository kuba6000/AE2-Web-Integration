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
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;

class WorldSchemaMigrationTest {

    @TempDir
    Path directory;

    @Test
    void openingReleasedSchemaNinePreservesPinsHistoryAndSettingsAcrossReopen() throws Exception {
        Path file = directory.resolve("released.sqlite");
        createReleasedDatabase(file, false);
        for (int opening = 0; opening < 2; opening++) {
            try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
                database.open();
                database.read(db -> {
                    assertScalar(db, "SELECT version FROM world_schema", "10");
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
                    return null;
                });
            }
        }
    }

    @Test
    void failedUpgradeRollsBackNewObjectsAndVersionBeforeRetry() throws Exception {
        Path file = directory.resolve("failed.sqlite");
        createReleasedDatabase(file, true);
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
    void schemaVersionControlsTheUpgradeWithoutRejectingAdditionalColumns() throws Exception {
        Path file = directory.resolve("extended.sqlite");
        createReleasedDatabase(file, false);
        try (Connection db = connect(file); Statement sql = db.createStatement()) {
            sql.execute("ALTER TABLE resource_pins ADD COLUMN extension TEXT");
            sql.execute("UPDATE resource_pins SET extension = 'Preserved extension'");
        }
        try (WorldDatabase database = new WorldDatabase(file, new ConfigSettings.ResourceHistory())) {
            database.open();
            database.read(db -> {
                assertScalar(db, "SELECT version FROM world_schema", "10");
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
        if (upgrade) createReleasedDatabase(file, false);
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
            assertScalar(db, "SELECT version FROM world_schema", upgrade ? "9" : "10");
            assertScalar(db, "SELECT count(*) FROM sqlite_master WHERE name = 'crafting_provider_intervals'", "0");
            if (upgrade) {
                assertScalar(db, "SELECT count(*) FROM sqlite_master WHERE name = 'home_pin_groups'", "0");
                assertScalar(
                    db,
                    "SELECT count(*) FROM pragma_table_info('resource_pins') WHERE name = 'home_group_key'",
                    "0");
            }
        }
    }

    private static void createReleasedDatabase(Path file, boolean rejectNewVersion) throws Exception {
        String fixture;
        try (
            InputStream input = WorldSchemaMigrationTest.class.getResourceAsStream("/persistence/world-schema-9.sql")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            fixture = new String(bytes.toByteArray(), UTF_8);
        }
        if (rejectNewVersion) fixture = fixture
            .replace("CHECK (typeof(version) = 'integer')", "CHECK (typeof(version) = 'integer' AND version = 9)");
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
            sql.execute(
                "INSERT INTO resource_pins VALUES ('AgICAgICAgICAgICAgICAg', 'admin', 1, 1, 2, 4, 1, 1, 0, 1, 1, 1, 0, 3600000, 1, 900000)");
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
