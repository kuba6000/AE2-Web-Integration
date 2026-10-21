package pl.kuba6000.ae2webintegration.core.tracking.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.persistence.WorldGridStore;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;

class CraftingHistoryStoreTest {

    @TempDir
    Path directory;

    @Test
    void completedJobWithUnkeyedResourcesAndProviderLocationsSurvivesReopen() throws Exception {
        StableKey grid = StableKey.random();
        StableKey output = StableKey.random();
        long now = System.currentTimeMillis();
        CompletedCrafting job = new CompletedCrafting(
            new ResourceSnapshot(
                new ResourceDescriptor("test", "product", 0, 0, "Product", ResourceType.ITEM, output, null),
                64),
            now - 1000,
            now,
            true,
            Collections.singletonList(
                new CompletedCrafting.Resource(
                    new ResourceSnapshot(
                        new ResourceDescriptor(null, null, 0, 0, "Essentia", ResourceType.OTHER, null, null),
                        80),
                    500,
                    Collections.singletonList(new CompletedCrafting.Interval(now - 900, now - 400)),
                    Collections.singletonList("Furnaces"))),
            Collections.singletonList(
                new CompletedCrafting.Provider(
                    "Furnaces",
                    Collections.singletonList(new DimensionalCoords("overworld", 1, 2, 3)),
                    Collections.singletonList(new CompletedCrafting.Interval(now - 800, now - 300)))));
        long id;
        try (WorldDatabase database = database()) {
            register(database, grid);
            HsqlCraftingHistoryStore store = new HsqlCraftingHistoryStore(database);
            id = store.append(grid, job);
        }
        try (WorldDatabase database = database()) {
            HsqlCraftingHistoryStore store = new HsqlCraftingHistoryStore(database);
            assertEquals(job, store.read(grid, id));
            assertNull(store.read(StableKey.random(), id));
            assertEquals(
                1,
                store.list(grid, null, 50)
                    .entries()
                    .size());
            assertEquals(
                id,
                store.list(grid, null, 50)
                    .entries()
                    .get(0)
                    .id());
            assertNull(
                store.list(grid, null, 50)
                    .nextBefore());
        }
    }

    @Test
    void queuedCompletionRetriesAfterIdentityBecomesDurableAndCloseDrains() throws Exception {
        StableKey grid = StableKey.random();
        long now = System.currentTimeMillis();
        CompletedCrafting job = new CompletedCrafting(
            new ResourceSnapshot(
                new ResourceDescriptor(null, null, 0, 0, "Unknown product", ResourceType.OTHER, null, null),
                1),
            now - 10,
            now,
            false,
            Collections.emptyList(),
            Collections.emptyList());
        try (WorldDatabase database = database()) {
            HsqlCraftingHistoryStore store = new HsqlCraftingHistoryStore(database);
            List<Runnable> scheduled = new ArrayList<>();
            CraftingHistoryService service = new CraftingHistoryService(store, scheduled::add);
            assertTrue(service.enqueue(grid, job));
            assertThrows(IOException.class, service::flush);
            register(database, grid);
            service.close();
            assertEquals(
                job,
                service.read(
                    grid,
                    service.list(grid, null, 10)
                        .entries()
                        .get(0)
                        .id()));
            assertFalse(service.enqueue(grid, job));
            for (Runnable task : scheduled) task.run();
            assertEquals(
                1,
                store.list(grid, null, 10)
                    .entries()
                    .size());
        }
    }

    @Test
    void paginationAndWholeJobCapPreserveSeparateProviderLocations() throws Exception {
        StableKey grid = StableKey.random();
        ConfigSettings.CraftingHistory policy = new ConfigSettings.CraftingHistory();
        policy.historyMaxJobsPerGrid = 2;
        long now = System.currentTimeMillis();
        try (WorldDatabase database = new WorldDatabase(
            directory.resolve("world"),
            new ConfigSettings.ResourceHistory(),
            policy)) {
            register(database, grid);
            HsqlCraftingHistoryStore store = new HsqlCraftingHistoryStore(database);
            long first = store.append(grid, simpleJob(now - 2, 1));
            long second = store.append(grid, simpleJob(now - 1, 2));
            long third = store.append(grid, simpleJob(now, 3));
            assertNull(store.read(grid, first));
            HsqlCraftingHistoryStore.Page page = store.list(grid, null, 1);
            assertEquals(
                third,
                page.entries()
                    .get(0)
                    .id());
            assertEquals(third, page.nextBefore());
            HsqlCraftingHistoryStore.Page next = store.list(grid, page.nextBefore(), 1);
            assertEquals(
                second,
                next.entries()
                    .get(0)
                    .id());
            assertNull(next.nextBefore());
            CompletedCrafting secondJob = store.read(grid, second);
            CompletedCrafting thirdJob = store.read(grid, third);
            assertNotNull(secondJob);
            assertNotNull(thirdJob);
            assertEquals(
                2,
                secondJob.providers()
                    .get(0)
                    .locations()
                    .get(0)
                    .x());
            assertEquals(
                3,
                thirdJob.providers()
                    .get(0)
                    .locations()
                    .get(0)
                    .x());
        }
    }

    @Test
    void expiredJobsAndFailedWritesDoNotPublishPartialHistory() throws Exception {
        StableKey grid = StableKey.random();
        long now = System.currentTimeMillis();
        try (WorldDatabase database = database()) {
            register(database, grid);
            HsqlCraftingHistoryStore store = new HsqlCraftingHistoryStore(database);
            long old = store.append(grid, simpleJob(now - TimeUnit.DAYS.toMillis(20), 1));
            assertNull(store.read(grid, old));
            CompletedCrafting good = simpleJob(now, 2);
            CompletedCrafting invalid = new CompletedCrafting(
                good.finalOutput(),
                good.timeStarted(),
                good.timeDone(),
                false,
                Collections.singletonList(
                    new CompletedCrafting.Resource(
                        good.finalOutput(),
                        10,
                        Collections.emptyList(),
                        Collections.singletonList("Missing provider"))),
                Collections.emptyList());
            assertThrows(IOException.class, () -> store.append(grid, invalid));
            assertTrue(
                store.list(grid, null, 10)
                    .entries()
                    .isEmpty());
            long saved = store.append(grid, good);
            assertEquals(good, store.read(grid, saved));
        }
    }

    @Test
    void stockCleanupCannotRemoveTheProductDescriptionOfRetainedCrafting() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        ResourceDescriptor description = new ResourceDescriptor(
            "test",
            "product",
            0,
            0,
            "Product",
            ResourceType.ITEM,
            item,
            null);
        CompletedCrafting job = new CompletedCrafting(
            new ResourceSnapshot(description, 99),
            now - 1000,
            now,
            false,
            Collections.emptyList(),
            Collections.emptyList());
        ConfigSettings.ResourceHistory policy = new ConfigSettings.ResourceHistory();
        policy.historyMaxRowsPerGrid = 1;
        try (WorldDatabase database = new WorldDatabase(directory.resolve("world"), policy)) {
            register(database, grid);
            HsqlCraftingHistoryStore store = new HsqlCraftingHistoryStore(database);
            long id = store.append(grid, job);
            HsqlStorageHistoryStore stock = new HsqlStorageHistoryStore(database);
            stock.append(
                grid,
                Collections.singletonMap(item, description),
                Collections.singletonList(
                    new StorageHistoryPoint(now, 1, StorageHistoryPoint.Kind.STOCK, item, 99, 0, 0, 0, 0, null)));
            stock.append(
                grid,
                Collections.emptyMap(),
                Collections.singletonList(
                    new StorageHistoryPoint(now + 1, 1, StorageHistoryPoint.Kind.GAP, null, 0, 0, 0, 0, 0, null)));
            assertEquals(job, store.read(grid, id));
        }
    }

    @Test
    void maintenanceAppliesReloadedBoundsToRetiredGridsWithoutAnotherCompletion() throws Exception {
        StableKey grid = StableKey.random();
        long now = System.currentTimeMillis();
        try (WorldDatabase database = database()) {
            register(database, grid);
            GridIdentityRegistry identities = new GridIdentityRegistry();
            identities.initialize(database, directory.resolve("absent.json"));
            HsqlCraftingHistoryStore store = new HsqlCraftingHistoryStore(database);
            long expired = store.append(grid, simpleJob(now - TimeUnit.DAYS.toMillis(2), 1));
            long capped = store.append(grid, simpleJob(now - 20000, 2));
            long retained = store.append(grid, simpleJob(now - 10000, 3));
            identities.controllerRemoved(new DimensionalCoords("overworld", 0, 0, 0));
            database.flushGridIdentities();
            assertNull(identities.getPersistentData(grid));
            ConfigSettings.CraftingHistory policy = new ConfigSettings.CraftingHistory();
            policy.historyRetentionDays = new ConfigSettings.Range(1, 1, 1);
            policy.historyMaxJobsPerGrid = 3;
            assertTrue(database.applyPolicy(database.policy(), policy));
            CraftingHistoryService service = new CraftingHistoryService(
                store,
                task -> fail("Maintenance has no completed jobs to schedule"));
            service.maintain();
            assertNull(service.read(grid, expired));
            assertEquals(simpleJob(now - 20000, 2), service.read(grid, capped));
            assertEquals(simpleJob(now - 10000, 3), service.read(grid, retained));
            assertEquals(
                2,
                service.list(grid, null, 10)
                    .entries()
                    .size());
            ConfigSettings.CraftingHistory cappedPolicy = new ConfigSettings.CraftingHistory();
            cappedPolicy.historyRetentionDays = new ConfigSettings.Range(1, 1, 1);
            cappedPolicy.historyMaxJobsPerGrid = 1;
            assertTrue(database.applyPolicy(database.policy(), cappedPolicy));
            service.maintain();
            assertNull(service.read(grid, capped));
            assertEquals(
                retained,
                service.list(grid, null, 10)
                    .entries()
                    .get(0)
                    .id());
            assertEquals(
                1,
                service.list(grid, null, 10)
                    .entries()
                    .size());
            service.close();
        }
    }

    private CompletedCrafting simpleJob(long now, int x) {
        return new CompletedCrafting(
            new ResourceSnapshot(
                new ResourceDescriptor(null, null, 0, 0, "Unknown product", ResourceType.OTHER, null, null),
                1),
            now - 100,
            now,
            false,
            Collections.emptyList(),
            Collections.singletonList(
                new CompletedCrafting.Provider(
                    "Machines",
                    Collections.singletonList(new DimensionalCoords("overworld", x, 2, 3)),
                    Collections.singletonList(new CompletedCrafting.Interval(now - 80, now - 20)))));
    }

    private WorldDatabase database() {
        return new WorldDatabase(directory.resolve("world"), new ConfigSettings.ResourceHistory());
    }

    private void register(WorldDatabase database, StableKey grid) throws Exception {
        WorldGridStore store = new WorldGridStore(database);
        store.load(directory.resolve("absent.json"));
        store.save(
            Collections.emptyMap(),
            Collections.singletonMap(
                grid,
                new GridPersistentData(
                    Collections.singleton(new DimensionalCoords("overworld", 0, 0, 0)),
                    new GridSettingsData(database.policy()))),
            Collections.singleton(grid));
    }
}
