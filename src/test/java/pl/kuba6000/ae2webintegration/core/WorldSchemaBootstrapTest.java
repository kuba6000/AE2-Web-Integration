package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.ResourceHistory;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;

class WorldSchemaBootstrapTest {

    @TempDir
    Path directory;

    @Test
    void anInterruptedUncommittedSchemaDoesNotPublishPartialInitialization() throws Exception {
        Path file = directory.resolve("interrupted.sqlite");
        try (Connection db = connect(file); Statement sql = db.createStatement()) {
            db.setAutoCommit(false);
            sql.execute("CREATE TABLE world_schema (singleton INTEGER PRIMARY KEY, version INTEGER)");
            sql.execute("INSERT INTO world_schema VALUES (1, 7)");
            sql.execute("CREATE TABLE grids (value INTEGER)");
        }
        try (WorldDatabase database = new WorldDatabase(file, new ResourceHistory())) {
            database.open();
            database.read(db -> {
                try (Statement sql = db.createStatement();
                    ResultSet rows = sql.executeQuery("SELECT version FROM world_schema")) {
                    assertTrue(rows.next());
                    assertEquals(7, rows.getLong(1));
                    assertFalse(rows.next());
                }
                return null;
            });
        }
    }

    @Test
    void aFailedWorldTransactionRollsBackBothSchemaChangesAndRowsAcrossReopen() throws Exception {
        Path file = directory.resolve("rollback.sqlite");
        try (WorldDatabase database = new WorldDatabase(file, new ResourceHistory())) {
            assertThrows(IOException.class, () -> database.write(db -> {
                try (Statement sql = db.createStatement()) {
                    sql.execute("CREATE TABLE transaction_probe (value INTEGER)");
                    sql.execute("INSERT INTO transaction_probe VALUES (42)");
                    sql.execute("UPDATE world_schema SET identities_imported = 1");
                }
                throw new IOException("Interrupted transaction");
            }));
        }
        try (WorldDatabase reopened = new WorldDatabase(file, new ResourceHistory())) {
            reopened.read(db -> {
                try (Statement sql = db.createStatement()) {
                    try (ResultSet rows = sql.executeQuery("SELECT identities_imported FROM world_schema")) {
                        assertTrue(rows.next());
                        assertFalse(rows.getBoolean(1));
                    }
                    try (ResultSet rows = sql
                        .executeQuery("SELECT 1 FROM sqlite_master WHERE name = 'transaction_probe'")) {
                        assertFalse(rows.next());
                    }
                }
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void aForeignOrIncompleteSchemaIsRejectedBeforeAnyPersistentChange(int kind) throws Exception {
        Path file = directory.resolve("foreign-" + kind + ".sqlite");
        try (Connection db = connect(file); Statement sql = db.createStatement()) {
            if (kind == 0) {
                sql.execute("CREATE TABLE unrelated (value INTEGER)");
                sql.execute("INSERT INTO unrelated VALUES (42)");
            } else if (kind == 1) {
                sql.execute(
                    "CREATE TABLE world_schema (singleton INTEGER, version INTEGER, identities_imported INTEGER)");
                sql.execute("INSERT INTO world_schema VALUES (1, 7, 0)");
            } else {
                sql.execute(
                    "CREATE VIEW world_schema AS SELECT 1 AS singleton, 7 AS version, 0 AS identities_imported");
            }
        }
        byte[] original = Files.readAllBytes(file);
        try (WorldDatabase database = new WorldDatabase(file, new ResourceHistory())) {
            assertThrows(IOException.class, database::open);
            assertThrows(IOException.class, database::open);
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + "-wal")));
    }

    private static Connection connect(Path file) throws Exception {
        return new JDBC().connect("jdbc:sqlite:" + file.toUri(), new Properties());
    }
}
