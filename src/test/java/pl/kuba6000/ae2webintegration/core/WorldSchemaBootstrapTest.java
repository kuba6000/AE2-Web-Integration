package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.config.DataPolicy;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;

class WorldSchemaBootstrapTest {

    private static final String INITIALIZATION_MARKER = """
        CREATE TABLE world_schema (
            singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
            version INTEGER NOT NULL,
            identities_imported BOOLEAN NOT NULL,
            initialized BOOLEAN NOT NULL
        ) AS (VALUES (1, 3, FALSE, FALSE)) WITH DATA
        """;

    @TempDir
    Path directory;

    @Test
    void incompleteInitializationRecoversAfterEveryDdlStage() throws Exception {
        DataPolicy policy = DataPolicy.from(new ConfigSettings.Data());
        List<String> stages = new ArrayList<>();
        try (WorldDatabase pristine = new WorldDatabase(directory.resolve("pristine"), policy)) {
            pristine.write(db -> {
                try (Statement sql = db.createStatement(); ResultSet script = sql.executeQuery("SCRIPT")) {
                    while (script.next()) {
                        String command = script.getString(1);
                        if (command.contains("PUBLIC.") && !command.contains("WORLD_SCHEMA")
                            && (command.startsWith("CREATE CACHED TABLE") || command.startsWith("CREATE MEMORY TABLE")
                                || command.startsWith("CREATE INDEX")
                                || command.startsWith("CREATE SEQUENCE")
                                || command.startsWith("ALTER TABLE")
                                || command.startsWith("ALTER SEQUENCE"))) {
                            stages.add(command);
                        }
                    }
                }
                return null;
            });
        }
        assertFalse(stages.isEmpty());
        // -1 is interruption before the atomic marker; 0 is immediately after it.
        for (int completed = -1; completed <= stages.size(); completed++) {
            Path path = directory.resolve("interrupted-" + completed);
            try (Connection db = connect(path); Statement sql = db.createStatement()) {
                if (completed >= 0) sql.execute(INITIALIZATION_MARKER);
                for (int stage = 0; stage < completed; stage++) sql.execute(stages.get(stage));
                sql.execute("SHUTDOWN");
            }
            try (WorldDatabase recovered = new WorldDatabase(path, policy)) {
                recovered.open();
                recovered.read(db -> {
                    try (Statement sql = db.createStatement();
                        ResultSet result = sql.executeQuery("SELECT version, initialized FROM world_schema")) {
                        assertTrue(result.next());
                        assertEquals(3, result.getInt(1));
                        assertTrue(result.getBoolean(2));
                        assertFalse(result.next());
                    }
                    return null;
                });
            }
        }
    }

    @Test
    void anUnknownSequenceDoesNotAdmitInitializationOrAdvanceTheSequence() throws Exception {
        Path path = directory.resolve("unknown-sequence");
        try (Connection db = connect(path); Statement sql = db.createStatement()) {
            sql.execute("CREATE SEQUENCE history_event_ids AS BIGINT START WITH 42");
            sql.execute("SHUTDOWN");
        }
        try (WorldDatabase rejected = new WorldDatabase(path, DataPolicy.from(new ConfigSettings.Data()))) {
            assertThrows(IOException.class, rejected::open);
        }
        try (Connection db = connect(path); Statement sql = db.createStatement()) {
            try (ResultSet tables = db.getMetaData()
                .getTables(null, "PUBLIC", null, new String[] { "TABLE" })) {
                assertFalse(tables.next());
            }
            try (ResultSet value = sql.executeQuery("VALUES NEXT VALUE FOR history_event_ids")) {
                assertTrue(value.next());
                assertEquals(42, value.getLong(1));
            }
            sql.execute("SHUTDOWN");
        }
    }

    @Test
    void recoveryRejectsUnknownObjectsOrApplicationDataWithoutChangingThem() throws Exception {
        DataPolicy policy = DataPolicy.from(new ConfigSettings.Data());
        for (boolean marker : new boolean[] { false, true }) {
            Path path = directory.resolve("unknown-" + marker);
            try (Connection db = connect(path); Statement sql = db.createStatement()) {
                if (marker) sql.execute(INITIALIZATION_MARKER);
                sql.execute("CREATE TABLE unrelated (value INTEGER)");
                sql.execute("INSERT INTO unrelated VALUES (42)");
                sql.execute("SHUTDOWN");
            }
            try (WorldDatabase rejected = new WorldDatabase(path, policy)) {
                assertThrows(IOException.class, rejected::open);
            }
            try (Connection db = connect(path); Statement sql = db.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT value FROM unrelated")) {
                    assertTrue(rows.next());
                    assertEquals(42, rows.getInt(1));
                }
                sql.execute("SHUTDOWN");
            }
        }
        Path path = directory.resolve("nonempty-owned-table");
        try (Connection db = connect(path); Statement sql = db.createStatement()) {
            sql.execute(INITIALIZATION_MARKER);
            sql.execute("CREATE TABLE grids (value INTEGER)");
            sql.execute("INSERT INTO grids VALUES (43)");
            sql.execute("SHUTDOWN");
        }
        try (WorldDatabase rejected = new WorldDatabase(path, policy)) {
            assertThrows(IOException.class, rejected::open);
        }
        try (Connection db = connect(path); Statement sql = db.createStatement()) {
            try (ResultSet rows = sql.executeQuery("SELECT value FROM grids")) {
                assertTrue(rows.next());
                assertEquals(43, rows.getInt(1));
            }
            sql.execute("SHUTDOWN");
        }
    }

    private static Connection connect(Path path) throws Exception {
        return DriverManager.getConnection(
            "jdbc:hsqldb:file:" + path.toString()
                .replace('\\', '/'),
            "SA",
            "");
    }
}
