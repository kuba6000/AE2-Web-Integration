package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.Data;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.persistence.WorldGridStore;

class WorldDatabaseTest {

    @TempDir
    Path directory;

    @Test
    void failedDependencyFlushBlocksTheMutationAndPeriodicRetryKeepsPendingIdentity() throws Exception {
        Path path = directory.resolve("retry");
        Path source = directory.resolve("retry.json");
        try (WorldDatabase database = new WorldDatabase(path, new ConfigSettings.Data())) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, source);
            database.write(db -> {
                try (Statement statement = db.createStatement()) {
                    statement
                        .execute("ALTER TABLE grids ADD CONSTRAINT temporary_write_failure CHECK (name <> 'Retry')");
                }
                return null;
            });
            TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(
                89102,
                false,
                AEControllerState.CONTROLLER_ONLINE);
            registry.controllerValidated(grid);
            StableKey key = registry.getKey(grid);
            assertNotNull(key);
            GridPersistentData data = registry.getPersistentData(key);
            assertNotNull(data);
            data.getSettings()
                .setName("Retry");
            AtomicInteger mutations = new AtomicInteger();
            assertThrows(IOException.class, () -> database.write(db -> mutations.incrementAndGet()));
            assertEquals(0, mutations.get());
            assertEquals(key, registry.getKey(grid));
            assertTrue(
                new WorldGridStore(database).load(source)
                    .isEmpty());
            ManualScheduler clock = new ManualScheduler();
            try {
                database.startGridFlush(clock, () -> {});
                clock.advance(TimeUnit.MINUTES.toMillis(5));
                assertTrue(
                    new WorldGridStore(database).load(source)
                        .isEmpty());
                // Repair through a separate JDBC connection, as an operator would; dependent writes remain guarded.
                try (Connection repair = DriverManager.getConnection(
                    "jdbc:hsqldb:file:" + path.toString()
                        .replace('\\', '/'),
                    "SA",
                    ""); Statement statement = repair.createStatement()) {
                    statement.execute("ALTER TABLE grids DROP CONSTRAINT temporary_write_failure");
                }
                clock.advance(TimeUnit.MINUTES.toMillis(5));
                assertEquals(
                    "Retry",
                    new WorldGridStore(database).load(source)
                        .get(key)
                        .getSettings()
                        .getName());
                assertEquals(
                    1,
                    database.write(db -> mutations.incrementAndGet())
                        .intValue());
            } finally {
                clock.shutdownNow();
            }
        }
    }

    @Test
    void periodicFlushSavesWithoutConsumersAndStoppedWorldCannotFlushItsReplacement() throws Exception {
        Path source = directory.resolve("periodic.json");
        GridIdentityRegistry registry = new GridIdentityRegistry();
        try (WorldDatabase first = new WorldDatabase(directory.resolve("first"), new ConfigSettings.Data());
            WorldDatabase second = new WorldDatabase(directory.resolve("second"), new ConfigSettings.Data())) {
            ManualScheduler clock = new ManualScheduler();
            try {
                registry.initialize(first, source);
                first.startGridFlush(clock, () -> {});
                TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(
                    89101,
                    false,
                    AEControllerState.CONTROLLER_ONLINE);
                registry.controllerValidated(grid);
                StableKey key = registry.getKey(grid);
                WorldGridStore saved = new WorldGridStore(first);
                clock.advance(TimeUnit.MINUTES.toMillis(5) - 1);
                assertTrue(
                    saved.load(source)
                        .isEmpty());
                clock.advance(1);
                assertTrue(
                    saved.load(source)
                        .containsKey(key));
                Runnable alreadyDispatched = clock.dispatch();
                first.stopGridFlush();
                first.finishGridIdentities();
                registry.clear();
                registry.initialize(second, source);
                registry.controllerValidated(grid);
                alreadyDispatched.run();
                clock.advance(TimeUnit.MINUTES.toMillis(10));
                assertTrue(
                    first.write(db -> true)
                        .booleanValue());
                assertTrue(
                    new WorldGridStore(second).load(source)
                        .isEmpty());
            } finally {
                clock.shutdownNow();
            }
        }
    }

    /** Drives the real scheduler boundary without waiting five wall-clock minutes. */
    static final class ManualScheduler extends ScheduledThreadPoolExecutor {

        private Runnable action;
        private ScheduledFuture<?> future;
        private long now;
        private long next;
        private long interval;
        private final List<Runnable> submitted = new ArrayList<>();

        ManualScheduler() {
            super(1);
        }

        @Override
        public void execute(@NotNull Runnable command) {
            submitted.add(command);
        }

        void runSubmitted() {
            List<Runnable> ready = new ArrayList<>(submitted);
            submitted.clear();
            ready.forEach(Runnable::run);
        }

        @Override
        public @NotNull ScheduledFuture<?> scheduleWithFixedDelay(@NotNull Runnable command, long initialDelay,
            long delay, TimeUnit unit) {
            action = command;
            next = unit.toMillis(initialDelay);
            interval = unit.toMillis(delay);
            future = super.scheduleWithFixedDelay(() -> {}, 1, 1, TimeUnit.DAYS);
            return future;
        }

        void advance(long millis) {
            now += millis;
            while (!future.isCancelled() && now >= next) {
                action.run();
                next += interval;
            }
        }

        Runnable dispatch() {
            return action;
        }
    }

    @Test
    void failedFinalFlushRejectsOldDependentWritesAfterTheRegistryMovesToAnotherWorld() throws Exception {
        Path source = directory.resolve("fenced.json");
        GridIdentityRegistry registry = new GridIdentityRegistry();
        try (WorldDatabase first = new WorldDatabase(directory.resolve("old"), new ConfigSettings.Data());
            WorldDatabase second = new WorldDatabase(directory.resolve("new"), new ConfigSettings.Data())) {
            registry.initialize(first, source);
            first.write(db -> {
                try (Statement statement = db.createStatement()) {
                    statement.execute("ALTER TABLE grids ADD CONSTRAINT fail_final_flush CHECK (name <> 'Unsaved')");
                }
                return null;
            });
            TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(
                89103,
                false,
                AEControllerState.CONTROLLER_ONLINE);
            registry.controllerValidated(grid);
            StableKey key = registry.getKey(grid);
            assertNotNull(key);
            GridPersistentData data = registry.getPersistentData(key);
            assertNotNull(data);
            data.getSettings()
                .setName("Unsaved");
            assertThrows(IOException.class, first::finishGridIdentities);
            registry.clear();
            registry.initialize(second, source);
            registry.controllerValidated(grid);
            AtomicInteger mutations = new AtomicInteger();
            assertThrows(IOException.class, () -> first.write(db -> mutations.incrementAndGet()));
            assertEquals(0, mutations.get());
            assertTrue(
                new WorldGridStore(second).load(source)
                    .isEmpty());
            second.flushGridIdentities();
            assertTrue(
                new WorldGridStore(second).load(source)
                    .containsKey(registry.getKey(grid)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void failedImportPreservesSourceAndCanBeRetriedAtomically(boolean duplicateController) throws Exception {
        StableKey key = TestGridFixtures.key(34);
        StableKey other = TestGridFixtures.key(35);
        Path source = directory.resolve("legacy.json");
        String valid = importJson(key);
        String invalid = duplicateController
            ? valid.substring(0, valid.length() - 1) + "," + importJson(other).substring(1)
            : "{broken";
        Files.write(source, invalid.getBytes(StandardCharsets.UTF_8));
        Data policy = new ConfigSettings.Data();
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            assertThrows(IOException.class, () -> registry.initialize(database, source));
            assertFalse(registry.isInitialized());
            assertFalse(Files.exists(source.resolveSibling("legacy.json.old")));
            assertArrayEquals(invalid.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(source));
            Files.write(source, importJson(other).getBytes(StandardCharsets.UTF_8));
            registry.initialize(database, source);
            assertNull(registry.getPersistentData(key));
            assertNotNull(registry.getPersistentData(other));
        }
    }

    @Test
    void failedSettingsTransactionKeepsDirtyValuesForRetryWithoutPartialPublication() throws Exception {
        Path source = directory.resolve("legacy.json");
        StableKey key = TestGridFixtures.key(38);
        Files.write(source, importJson(key).getBytes(StandardCharsets.UTF_8));
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), new ConfigSettings.Data())) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, source);
            database.write(db -> {
                try (Statement statement = db.createStatement()) {
                    statement
                        .execute("ALTER TABLE grids ADD CONSTRAINT temporary_write_failure CHECK (name <> 'Retry')");
                }
                return null;
            });
            GridSettingsData settings = registry.getPersistentData(key)
                .getSettings();
            settings.setName("Retry");
            settings.setHistoryRetentionDays(14);
            assertThrows(IOException.class, registry::saveIfDirty);
            GridIdentityRegistry durable = new GridIdentityRegistry();
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
                try (Statement statement = db.createStatement()) {
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
        StableKey key = TestGridFixtures.key(36);
        Data policy = new ConfigSettings.Data();
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, source);
        }
        Files.write(source, importJson(key).getBytes(StandardCharsets.UTF_8));
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, source);
            assertNull(registry.getPersistentData(key));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 999 })
    void unsupportedSchemaCannotPublishOrEraseExistingGridData(int version) throws Exception {
        Path path = directory.resolve("world");
        Path source = directory.resolve("legacy.json");
        StableKey key = TestGridFixtures.key(37);
        Files.write(source, importJson(key).getBytes(StandardCharsets.UTF_8));
        Data policy = new ConfigSettings.Data();
        try (WorldDatabase database = new WorldDatabase(path, policy)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, source);
            // Schema metadata is external persisted input, independent of the running mod's version.
            database.write(db -> {
                try (Statement statement = db.createStatement()) {
                    statement.executeUpdate("UPDATE world_schema SET version = " + version);
                }
                return null;
            });
        }
        try (WorldDatabase unsupported = new WorldDatabase(path, policy)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            assertThrows(IOException.class, () -> registry.initialize(unsupported, source));
            assertFalse(registry.isInitialized());
        }
        try (Connection connection = DriverManager.getConnection(
            "jdbc:hsqldb:file:" + path.toString()
                .replace('\\', '/'),
            "SA",
            ""); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT version FROM world_schema")) {
                assertTrue(result.next());
                assertEquals(version, result.getInt(1));
            }
            statement.executeUpdate("UPDATE world_schema SET version = 3");
            statement.execute("SHUTDOWN");
        }
        try (WorldDatabase restored = new WorldDatabase(path, policy)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(restored, source);
            assertNotNull(registry.getPersistentData(key));
        }
    }

    private static String importJson(StableKey key) {
        return "{\"" + key
            + "\":{\"controllers\":[{\"dimensionId\":\"test:dimension\",\"x\":1,\"y\":2,\"z\":3}],\"settings\":{\"name\":\"Preserved\"}}}";
    }

    @Test
    void importsReleasedIdentitiesOnceAndRetainsDatabaseSettings() throws Exception {
        StableKey key = TestGridFixtures.key(33);
        Path json = directory.resolve("grid-identities.json");
        Files.write(
            json,
            ("{\"" + key
                + "\":{\"controllers\":[{\"dimid\":\"0\",\"x\":1,\"y\":2,\"z\":3}],"
                + "\"settings\":{\"name\":\"Imported\",\"isTracked\":true,\"historyRetentionDays\":14}}}")
                .getBytes(StandardCharsets.UTF_8));
        Data policy = new ConfigSettings.Data();
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, json);
            assertEquals(key, registry.findIdentity(new DimensionalCoords(0, 1, 2, 3)));
            GridPersistentData data = registry.getPersistentData(key);
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
            GridIdentityRegistry registry = new GridIdentityRegistry();
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
        assertFalse(Files.exists(json));
        assertTrue(
            new String(Files.readAllBytes(json.resolveSibling("grid-identities.json.old")), StandardCharsets.UTF_8)
                .contains("Imported"));
    }

    @Test
    void existingArchiveIsPreservedAndDoesNotUndoCommittedImport() throws Exception {
        Path source = directory.resolve("grid-identities.json");
        Path archived = directory.resolve("grid-identities.json.old");
        StableKey key = TestGridFixtures.key(39);
        byte[] original = importJson(key).getBytes(StandardCharsets.UTF_8);
        byte[] previous = "previous backup".getBytes(StandardCharsets.UTF_8);
        Files.write(source, original);
        Files.write(archived, previous);
        Data policy = new ConfigSettings.Data();
        try (WorldDatabase database = new WorldDatabase(directory.resolve("grids"), policy)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, source);
            assertNotNull(registry.getPersistentData(key));
        }
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(previous, Files.readAllBytes(archived));
        Files.write(source, "{broken".getBytes(StandardCharsets.UTF_8));
        try (WorldDatabase database = new WorldDatabase(directory.resolve("grids"), policy)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, source);
            assertNotNull(registry.getPersistentData(key));
        }
    }

    @Test
    void oneOwnerPerWorldCanReopenOnlyAfterClose() throws Exception {
        Data policy = new ConfigSettings.Data();
        Path path = directory.resolve("world");
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
