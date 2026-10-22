package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.ResourceHistory;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;

class WorldDatabaseSqliteTest {

    @TempDir
    Path directory;

    @Test
    void anUnsupported64BitSchemaVersionCannotNarrowToTheSupportedVersion() throws Exception {
        Path file = directory.resolve("large-version.sqlite");
        try (WorldDatabase database = new WorldDatabase(file, new ResourceHistory())) {
            database.write(db -> {
                try (Statement sql = db.createStatement()) {
                    sql.execute("UPDATE world_schema SET version = 4294967303");
                }
                return null;
            });
        }
        byte[] original = Files.readAllBytes(file);
        try (WorldDatabase database = new WorldDatabase(file, new ResourceHistory())) {
            assertThrows(IOException.class, database::open);
        }
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void aForeignSchemaWithASimilarInternalPrefixIsRejectedWithoutModification() throws Exception {
        Path file = directory.resolve("foreign.sqlite");
        try (Connection db = new JDBC().connect("jdbc:sqlite:" + file.toUri(), new Properties());
            Statement sql = db.createStatement()) {
            sql.execute("CREATE TABLE sqliteXdata (value INTEGER)");
            sql.execute("INSERT INTO sqliteXdata VALUES (42)");
        }
        byte[] original = Files.readAllBytes(file);
        try (WorldDatabase database = new WorldDatabase(file, new ResourceHistory())) {
            assertThrows(IOException.class, database::open);
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertFalse(Files.exists(file.resolveSibling("foreign.sqlite-wal")));
    }

    @Test
    void worldTransactionsUseDurableSqliteWithoutTouchingOldDatabaseFiles() throws Exception {
        byte[] oldContents = "Unconverted HSQL database sentinel".getBytes(StandardCharsets.UTF_8);
        Path oldData = directory.resolve("grids.data");
        Path oldProperties = directory.resolve("grids.properties");
        Files.write(oldData, oldContents);
        Files.write(oldProperties, oldContents);
        Path file = directory.resolve("grids.sqlite");
        try (WorldDatabase database = new WorldDatabase(file, new ResourceHistory())) {
            database.write(db -> {
                assertEquals(
                    "SQLite",
                    db.getMetaData()
                        .getDatabaseProductName());
                try (Statement sql = db.createStatement()) {
                    try (ResultSet rows = sql.executeQuery("PRAGMA journal_mode")) {
                        assertTrue(rows.next());
                        assertEquals("wal", rows.getString(1));
                    }
                    try (ResultSet rows = sql.executeQuery("PRAGMA synchronous")) {
                        assertTrue(rows.next());
                        assertEquals(2, rows.getInt(1));
                    }
                    try (ResultSet rows = sql.executeQuery("PRAGMA foreign_keys")) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                    try (ResultSet rows = sql.executeQuery("PRAGMA cache_size")) {
                        assertTrue(rows.next());
                        assertEquals(-16384, rows.getInt(1));
                    }
                }
                return null;
            });
            database.read(db -> {
                try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery("PRAGMA foreign_keys")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
                return null;
            });
        }
        assertTrue(Files.isRegularFile(file));
        assertArrayEquals(oldContents, Files.readAllBytes(oldData));
        assertArrayEquals(oldContents, Files.readAllBytes(oldProperties));
    }
}
