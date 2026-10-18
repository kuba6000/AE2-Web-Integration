package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.config.DataPolicy;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;

class WorldDatabaseTest {

    @TempDir
    Path directory;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void failedImportPreservesSourceAndCanBeRetriedAtomically(boolean duplicateController) throws Exception {
        var key = TestGridFixtures.key(34);
        var other = TestGridFixtures.key(35);
        Path source = directory.resolve("legacy.json");
        String valid = importJson(key);
        String invalid = duplicateController
            ? valid.substring(0, valid.length() - 1) + "," + importJson(other).substring(1)
            : "{broken";
        java.nio.file.Files.write(source, invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var policy = DataPolicy.from(new ConfigSettings.Data());
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            assertThrows(IOException.class, () -> registry.initialize(database, source));
            assertFalse(registry.isInitialized());
            assertFalse(java.nio.file.Files.exists(source.resolveSibling("legacy.json.old")));
            assertArrayEquals(
                invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.Files.readAllBytes(source));
            java.nio.file.Files.write(source, importJson(other).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            registry.initialize(database, source);
            assertNull(registry.getPersistentData(key));
            assertNotNull(registry.getPersistentData(other));
        }
    }

    @Test
    void failedSettingsTransactionKeepsDirtyValuesForRetryWithoutPartialPublication() throws Exception {
        Path source = directory.resolve("legacy.json");
        var key = TestGridFixtures.key(38);
        java.nio.file.Files.write(source, importJson(key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (WorldDatabase database = new WorldDatabase(
            directory.resolve("world"),
            DataPolicy.from(new ConfigSettings.Data()))) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            registry.initialize(database, source);
            database.write(db -> {
                try (var statement = db.createStatement()) {
                    statement
                        .execute("ALTER TABLE grids ADD CONSTRAINT temporary_write_failure CHECK (name <> 'Retry')");
                }
                return null;
            });
            var settings = registry.getPersistentData(key)
                .getSettings();
            settings.setName("Retry");
            settings.setHistoryRetentionDays(14);
            assertThrows(IOException.class, registry::saveIfDirty);
            var durable = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            durable.initialize(database, source);
            assertEquals(
                "Preserved",
                durable.getPersistentData(key)
                    .getSettings()
                    .getName());
            assertEquals(
                7,
                durable.getPersistentData(key)
                    .getSettings()
                    .getHistoryRetentionDays());
            database.write(db -> {
                try (var statement = db.createStatement()) {
                    statement.execute("ALTER TABLE grids DROP CONSTRAINT temporary_write_failure");
                }
                return null;
            });
            registry.saveIfDirty();
            durable.initialize(database, source);
            assertEquals(
                "Retry",
                durable.getPersistentData(key)
                    .getSettings()
                    .getName());
            assertEquals(
                14,
                durable.getPersistentData(key)
                    .getSettings()
                    .getHistoryRetentionDays());
        }
    }

    @Test
    void absentImportSourceIsCompletedAndLaterJsonCannotOverrideTheDatabase() throws Exception {
        Path source = directory.resolve("missing.json");
        var key = TestGridFixtures.key(36);
        var policy = DataPolicy.from(new ConfigSettings.Data());
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            registry.initialize(database, source);
        }
        java.nio.file.Files.write(source, importJson(key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            registry.initialize(database, source);
            assertNull(registry.getPersistentData(key));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = { 1, 999 })
    void unsupportedSchemaCannotPublishOrEraseExistingGridData(int version) throws Exception {
        Path path = directory.resolve("world");
        Path source = directory.resolve("legacy.json");
        var key = TestGridFixtures.key(37);
        java.nio.file.Files.write(source, importJson(key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var policy = DataPolicy.from(new ConfigSettings.Data());
        try (WorldDatabase database = new WorldDatabase(path, policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            registry.initialize(database, source);
            // Schema metadata is external persisted input, independent of the running mod's version.
            database.write(db -> {
                try (var statement = db.createStatement()) {
                    statement.executeUpdate("UPDATE world_schema SET version = " + version);
                }
                return null;
            });
        }
        try (WorldDatabase unsupported = new WorldDatabase(path, policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            assertThrows(IOException.class, () -> registry.initialize(unsupported, source));
            assertFalse(registry.isInitialized());
        }
        try (var connection = java.sql.DriverManager.getConnection(
            "jdbc:hsqldb:file:" + path.toString()
                .replace('\\', '/'),
            "SA",
            ""); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT version FROM world_schema")) {
                assertTrue(result.next());
                assertEquals(version, result.getInt(1));
            }
            statement.executeUpdate("UPDATE world_schema SET version = 2");
            statement.execute("SHUTDOWN");
        }
        try (WorldDatabase restored = new WorldDatabase(path, policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            registry.initialize(restored, source);
            assertNotNull(registry.getPersistentData(key));
        }
    }

    private static String importJson(pl.kuba6000.ae2webintegration.core.identity.StableKey key) {
        return "{\"" + key
            + "\":{\"controllers\":[{\"dimensionId\":\"test:dimension\",\"x\":1,\"y\":2,\"z\":3}],\"settings\":{\"name\":\"Preserved\"}}}";
    }

    @Test
    void importsReleasedIdentitiesOnceAndRetainsDatabaseSettings() throws Exception {
        var key = TestGridFixtures.key(33);
        Path json = directory.resolve("grid-identities.json");
        java.nio.file.Files.write(
            json,
            ("{\"" + key
                + "\":{\"controllers\":[{\"dimid\":\"0\",\"x\":1,\"y\":2,\"z\":3}],"
                + "\"settings\":{\"name\":\"Imported\",\"isTracked\":true,\"historyRetentionDays\":14}}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var policy = DataPolicy.from(new ConfigSettings.Data());
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            registry.initialize(database, json);
            assertEquals(
                key,
                registry.findIdentity(new pl.kuba6000.ae2webintegration.core.api.DimensionalCoords(0, 1, 2, 3)));
            var data = registry.getPersistentData(key);
            assertNotNull(data);
            assertEquals(
                "Imported",
                data.getSettings()
                    .getName());
            assertTrue(
                data.getSettings()
                    .isTracked());
            data.getSettings()
                .setName("Database");
            registry.saveIfDirty();
            registry.clear();
        }
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            registry.initialize(database, json);
            assertEquals(
                "Database",
                registry.getPersistentData(key)
                    .getSettings()
                    .getName());
            assertEquals(
                14,
                registry.getPersistentData(key)
                    .getSettings()
                    .getHistoryRetentionDays());
        }
        assertFalse(java.nio.file.Files.exists(json));
        assertTrue(
            new String(
                java.nio.file.Files.readAllBytes(json.resolveSibling("grid-identities.json.old")),
                java.nio.charset.StandardCharsets.UTF_8).contains("Imported"));
    }

    @Test
    void existingArchiveIsPreservedAndDoesNotUndoCommittedImport() throws Exception {
        Path source = directory.resolve("grid-identities.json");
        Path archived = directory.resolve("grid-identities.json.old");
        var key = TestGridFixtures.key(39);
        byte[] original = importJson(key).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] previous = "previous backup".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(source, original);
        java.nio.file.Files.write(archived, previous);
        var policy = DataPolicy.from(new ConfigSettings.Data());
        try (WorldDatabase database = new WorldDatabase(directory.resolve("grids"), policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            registry.initialize(database, source);
            assertNotNull(registry.getPersistentData(key));
        }
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(source));
        assertArrayEquals(previous, java.nio.file.Files.readAllBytes(archived));
        java.nio.file.Files.write(source, "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (WorldDatabase database = new WorldDatabase(directory.resolve("grids"), policy)) {
            var registry = new pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry();
            registry.initialize(database, source);
            assertNotNull(registry.getPersistentData(key));
        }
    }

    @Test
    void oneOwnerPerWorldCanReopenOnlyAfterClose() throws Exception {
        var policy = DataPolicy.from(new ConfigSettings.Data());
        var path = directory.resolve("world");
        try (WorldDatabase first = new WorldDatabase(path, policy);
            WorldDatabase second = new WorldDatabase(path, policy)) {
            first.open();
            assertThrows(IOException.class, second::open);
            first.close();
            second.open();
            assertThrows(IOException.class, first::open);
        }
        try (WorldDatabase reopened = new WorldDatabase(path, policy)) {
            reopened.open();
        }
    }
}
