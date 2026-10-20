package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint.Kind;

class LiveDataPolicyTest {

    @TempDir
    Path directory;

    @Test
    void scheduledPolicyApplicationRetriesAfterFailureWithoutConsumers() throws Exception {
        Path path = directory.resolve("idle");
        try (WorldDatabase database = new WorldDatabase(path, new ConfigSettings.Data());
            HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(database)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, directory.resolve("absent.json"));
            TestGridFixtures.TestGrid live = new TestGridFixtures.TestGrid(
                89502,
                false,
                AEControllerState.CONTROLLER_ONLINE);
            registry.controllerValidated(live);
            StableKey grid = registry.getKey(live);
            assertNotNull(grid);
            StableKey item = TestGridFixtures.key(82);
            long now = System.currentTimeMillis();
            history.append(
                grid,
                Collections.singletonMap(item, description(item)),
                Arrays.asList(
                    new StorageHistoryPoint(now, 1, Kind.STOCK, item, 1, 0, 0, 0, 0, null),
                    new StorageHistoryPoint(now + 1, 1, Kind.STOCK, item, 2, 0, 0, 0, 0, null)));
            registry.controllerRemoved(live.position());
            registry.saveIfDirty();
            database.write(db -> {
                try (Statement sql = db.createStatement()) {
                    sql.execute("ALTER TABLE grids ADD CONSTRAINT reject_policy CHECK (retention_days > 2)");
                }
                return null;
            });
            WorldDatabaseTest.ManualScheduler scheduler = new WorldDatabaseTest.ManualScheduler();
            try {
                database.startGridFlush(scheduler, history::maintain);
                ConfigSettings.Data tighter = new ConfigSettings.Data();
                tighter.historyRetentionDays = new ConfigSettings.Range(1, 2, 2);
                tighter.historyMaxRowsPerGrid = 1;
                assertTrue(database.applyPolicy(tighter));
                assertEquals(2, stockRows(database));
                scheduler.runSubmitted();
                assertEquals(2, stockRows(database));
                database.write(db -> {
                    try (Statement sql = db.createStatement()) {
                        sql.execute("ALTER TABLE grids DROP CONSTRAINT reject_policy");
                    }
                    return null;
                });
                scheduler.advance(TimeUnit.MINUTES.toMillis(5));
                assertEquals(1, stockRows(database));
                assertEquals(
                    1,
                    history.read(grid, item, now, 100)
                        .points()
                        .size());
                Runnable dispatched = scheduler.dispatch();
                database.stopGridFlush();
                database.finishGridIdentities();
                registry.clear();
                try (WorldDatabase next = new WorldDatabase(directory.resolve("next"), new ConfigSettings.Data())) {
                    registry.initialize(next, directory.resolve("absent.json"));
                    dispatched.run();
                    assertFalse(database.applyPolicy(new ConfigSettings.Data()));
                    assertEquals(7, registry.getDataPolicy().historyRetentionDays.defaultValue);
                }
            } finally {
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void appendWaitingAfterInitializationUsesTheNewWriterPolicy() throws Exception {
        ConfigSettings.Data initial = new ConfigSettings.Data();
        initial.historyMaxRowsPerGrid = 10;
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), initial);
            HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(database)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, directory.resolve("absent.json"));
            TestGridFixtures.TestGrid live = new TestGridFixtures.TestGrid(
                89500,
                false,
                AEControllerState.CONTROLLER_ONLINE);
            registry.controllerValidated(live);
            StableKey grid = registry.getKey(live);
            assertNotNull(grid);
            StableKey item = TestGridFixtures.key(80);
            long now = System.currentTimeMillis();
            List<StorageHistoryPoint> points = new ArrayList<>();
            for (int index = 0; index < 5; index++)
                points.add(new StorageHistoryPoint(now + index, 1, Kind.STOCK, item, index, 0, 0, 0, 0, null));
            history.append(grid, Collections.singletonMap(item, description(item)), points);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread append = new Thread(() -> {
                try {
                    history.append(
                        grid,
                        Collections.emptyMap(),
                        Collections
                            .singletonList(new StorageHistoryPoint(now + 6, 1, Kind.STOCK, item, 6, 0, 0, 0, 0, null)));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            synchronized (registry) {
                append.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (append.getState() != Thread.State.BLOCKED && append.isAlive() && System.nanoTime() < deadline)
                    Thread.yield();
                assertEquals(Thread.State.BLOCKED, append.getState());
                ConfigSettings.Data smaller = new ConfigSettings.Data();
                smaller.historyMaxRowsPerGrid = 2;
                assertTrue(database.applyPolicy(smaller));
                history.initialize();
            }
            append.join(5000);
            assertFalse(append.isAlive());
            assertNull(failure.get());
            assertEquals(
                2,
                history.read(grid, item, now, 100)
                    .points()
                    .size());
        }
    }

    @Test
    void policyRefreshExpiresRetiredSqlHistoryWithoutAnotherObservation() throws Exception {
        try (WorldDatabase database = new WorldDatabase(directory.resolve("retired"), new ConfigSettings.Data());
            HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(database)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, directory.resolve("absent.json"));
            TestGridFixtures.TestGrid live = new TestGridFixtures.TestGrid(
                89501,
                false,
                AEControllerState.CONTROLLER_ONLINE);
            registry.controllerValidated(live);
            StableKey grid = registry.getKey(live);
            assertNotNull(grid);
            StableKey item = TestGridFixtures.key(81);
            long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3);
            history.append(
                grid,
                Collections.singletonMap(item, description(item)),
                Collections.singletonList(new StorageHistoryPoint(old, 1, Kind.FLOW, item, 0, 5, 2, 0, 0, old - 1000)));
            registry.controllerRemoved(live.position());
            registry.saveIfDirty();
            ConfigSettings.Data shorter = new ConfigSettings.Data();
            shorter.historyRetentionDays = new ConfigSettings.Range(1, 2, 2);
            assertTrue(database.applyPolicy(shorter));
            history.initialize();
            assertTrue(
                history.read(grid, item, 0, 100)
                    .points()
                    .isEmpty());
            assertTrue(database.applyPolicy(new ConfigSettings.Data()));
            history.initialize();
            assertTrue(
                history.read(grid, item, 0, 100)
                    .points()
                    .isEmpty(),
                "Longer retention must not resurrect physically expired rows");
        }
    }

    private static ResourceDescriptor description(StableKey item) {
        return new ResourceDescriptor("test", "item", 0, 0, "Item", ResourceType.ITEM, item, null);
    }

    private static int stockRows(WorldDatabase database) throws IOException {
        return database.read(db -> {
            try (Statement sql = db.createStatement();
                ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM history_stock")) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }
}
