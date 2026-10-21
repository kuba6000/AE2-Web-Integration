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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
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
import pl.kuba6000.ae2webintegration.core.tracking.storage.SqliteStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;

class CraftingHistoryStoreTest {

    @TempDir
    Path directory;

    @Test
    void persistedOrderingPositionsAndResourceQuantitiesCannotBeNegative() throws Exception {
        StableKey grid = StableKey.random();
        long now = System.currentTimeMillis();
        CompletedCrafting base = simpleJob(now, 1);
        CompletedCrafting job = new CompletedCrafting(
            base.finalOutput(),
            base.timeStarted(),
            base.timeDone(),
            false,
            Collections.singletonList(
                new CompletedCrafting.Resource(
                    base.finalOutput(),
                    10,
                    Collections.singletonList(new CompletedCrafting.Interval(now - 10, now)),
                    Collections.singletonList("Machines"))),
            base.providers());
        try (WorldDatabase database = database()) {
            register(database, grid);
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            long id = store.append(grid, job);
            for (String table : new String[] { "crafting_resources", "crafting_providers",
                "crafting_provider_locations", "crafting_resource_intervals", "crafting_provider_intervals" }) {
                assertConstraintViolation(database, "UPDATE " + table + " SET ordinal = -1 WHERE ordinal = 0");
            }
            assertConstraintViolation(database, "UPDATE crafting_resources SET quantity = -1 WHERE ordinal = 1");
            assertEquals(job, store.read(grid, id));
        }
    }

    @Test
    void backwardWallClockAndDistinctProviderNamesPreserveTheirStoredValues() throws Exception {
        StableKey grid = StableKey.random();
        long now = System.currentTimeMillis();
        CompletedCrafting base = simpleJob(now, 1);
        List<CompletedCrafting.Interval> reversed = Collections
            .singletonList(new CompletedCrafting.Interval(now, now - 10));
        CompletedCrafting job = new CompletedCrafting(
            base.finalOutput(),
            now,
            now - 10,
            false,
            Collections.singletonList(
                new CompletedCrafting.Resource(
                    base.finalOutput(),
                    -10,
                    reversed,
                    Arrays.asList("Machines", "Machines "))),
            Arrays.asList(
                new CompletedCrafting.Provider(
                    "Machines",
                    Arrays.asList(
                        new DimensionalCoords("overworld", 1, 2, 3),
                        new DimensionalCoords("overworld", 4, 5, 6)),
                    reversed),
                new CompletedCrafting.Provider("Machines ", Collections.emptyList(), reversed)));
        long id;
        try (WorldDatabase database = database()) {
            register(database, grid);
            id = new SqliteCraftingHistoryStore(database).append(grid, job);
        }
        try (WorldDatabase database = database()) {
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            assertEquals(job, store.read(grid, id));
            assertEquals(
                job.finalOutput(),
                store.list(grid, null, 10)
                    .entries()
                    .get(0)
                    .finalOutput());
        }
    }

    @Test
    void providersCannotBeLinkedToResourcesFromAnotherJob() throws Exception {
        StableKey grid = StableKey.random();
        long now = System.currentTimeMillis();
        try (WorldDatabase database = database()) {
            register(database, grid);
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            long first = store.append(grid, simpleJob(now, 1));
            long second = store.append(grid, simpleJob(now, 2));
            assertConstraintViolation(
                database,
                "INSERT INTO crafting_resource_providers(job_id, crafting_resource_id, provider_id)" + " SELECT "
                    + first
                    + ", r.id, p.id FROM crafting_resources r, crafting_providers p"
                    + " WHERE r.job_id = "
                    + first
                    + " AND p.job_id = "
                    + second);
            assertConstraintViolation(
                database,
                "INSERT INTO crafting_resource_providers(job_id, crafting_resource_id, provider_id)" + " SELECT "
                    + second
                    + ", r.id, p.id FROM crafting_resources r, crafting_providers p"
                    + " WHERE r.job_id = "
                    + first
                    + " AND p.job_id = "
                    + second);
        }
    }

    @Test
    void finalProductRelationRejectsResourcesFromAnotherJob() throws Exception {
        StableKey grid = StableKey.random();
        long now = System.currentTimeMillis();
        CompletedCrafting first = simpleJob(now, 1);
        CompletedCrafting second = simpleJob(now, 2);
        try (WorldDatabase database = database()) {
            register(database, grid);
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            long firstId = store.append(grid, first);
            long secondId = store.append(grid, second);
            database.read(db -> {
                try (Statement sql = db.createStatement();
                    ResultSet rows = sql.executeQuery(
                        "SELECT p.job_id, r.job_id FROM crafting_products p JOIN crafting_resources r"
                            + " ON r.id = p.crafting_resource_id ORDER BY p.job_id")) {
                    assertTrue(rows.next());
                    assertEquals(firstId, rows.getLong(1));
                    assertEquals(firstId, rows.getLong(2));
                    assertTrue(rows.next());
                    assertEquals(secondId, rows.getLong(1));
                    assertEquals(secondId, rows.getLong(2));
                    assertFalse(rows.next());
                }
                return null;
            });
            assertConstraintViolation(
                database,
                "UPDATE crafting_products SET crafting_resource_id ="
                    + " (SELECT crafting_resource_id FROM crafting_products WHERE job_id = "
                    + secondId
                    + ")"
                    + " WHERE job_id = "
                    + firstId);
            assertEquals(first, store.read(grid, firstId));
            assertEquals(second, store.read(grid, secondId));
            assertConstraintViolation(
                database,
                "INSERT INTO crafting_products(job_id, crafting_resource_id) SELECT job_id, crafting_resource_id"
                    + " FROM crafting_products WHERE job_id = "
                    + firstId);
            database.write(db -> {
                try (Statement sql = db.createStatement()) {
                    sql.executeUpdate("DELETE FROM crafting_jobs WHERE id = " + firstId);
                    try (ResultSet rows = sql
                        .executeQuery("SELECT COUNT(*) FROM crafting_products WHERE job_id = " + firstId)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getLong(1));
                    }
                }
                return null;
            });
            assertNull(store.read(grid, firstId));
            assertEquals(second, store.read(grid, secondId));
        }
    }

    private static void assertConstraintViolation(WorldDatabase database, String statement) {
        IOException failure = assertThrows(IOException.class, () -> database.write(db -> {
            try (Statement sql = db.createStatement()) {
                sql.executeUpdate(statement);
            }
            return null;
        }));
        assertTrue(failure.getCause() instanceof SQLException);
        assertEquals(19, ((SQLException) failure.getCause()).getErrorCode() & 0xff);
    }

    @Test
    void negativeProductQuantityRollsBackTheWholeJob() throws Exception {
        StableKey grid = StableKey.random();
        CompletedCrafting good = simpleJob(System.currentTimeMillis(), 1);
        CompletedCrafting invalid = new CompletedCrafting(
            new ResourceSnapshot(
                good.finalOutput()
                    .description(),
                -1),
            good.timeStarted(),
            good.timeDone(),
            false,
            good.resources(),
            good.providers());
        try (WorldDatabase database = database()) {
            register(database, grid);
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            assertThrows(IOException.class, () -> store.append(grid, invalid));
            assertTrue(
                store.list(grid, null, 10)
                    .entries()
                    .isEmpty());
            long id = store.append(grid, good);
            assertEquals(good, store.read(grid, id));
        }
    }

    @Test
    void completedJobWithUnkeyedResourcesAndProviderLocationsSurvivesReopen() throws Exception {
        StableKey grid = StableKey.random();
        StableKey output = StableKey.random();
        long now = System.currentTimeMillis();
        CompletedCrafting job = new CompletedCrafting(
            new ResourceSnapshot(
                new ResourceDescriptor("test", "路径", 0, 0, "Produkt Żółć 日本語 🧪", ResourceType.ITEM, output, null),
                Long.MAX_VALUE),
            now - 1000,
            now,
            true,
            Collections.singletonList(
                new CompletedCrafting.Resource(
                    new ResourceSnapshot(
                        new ResourceDescriptor(null, null, 0, 0, "Essentia Łódź 🧪", ResourceType.OTHER, null, null),
                        80),
                    500,
                    Collections.singletonList(new CompletedCrafting.Interval(now - 900, now - 400)),
                    Collections.singletonList("工場 🛠️ "))),
            Collections.singletonList(
                new CompletedCrafting.Provider(
                    "工場 🛠️ ",
                    Collections.singletonList(new DimensionalCoords("test:świat/世界", 1, 2, 3)),
                    Collections.singletonList(new CompletedCrafting.Interval(now - 800, now - 300)))));
        long id;
        try (WorldDatabase database = database()) {
            register(database, grid);
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            id = store.append(grid, job);
        }
        try (WorldDatabase database = database()) {
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
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
    void rejectedHeadDoesNotBlockValidCompletionsAcrossGrids() throws Exception {
        StableKey firstGrid = StableKey.random();
        StableKey secondGrid = StableKey.random();
        CompletedCrafting good = simpleJob(System.currentTimeMillis(), 1);
        CompletedCrafting bad = new CompletedCrafting(
            new ResourceSnapshot(
                good.finalOutput()
                    .description(),
                -1),
            good.timeStarted(),
            good.timeDone(),
            false,
            good.resources(),
            good.providers());
        try (WorldDatabase database = database()) {
            register(database, firstGrid);
            register(database, secondGrid, 1);
            CraftingHistoryService service = new CraftingHistoryService(
                new SqliteCraftingHistoryStore(database),
                task -> {});
            assertTrue(service.enqueue(firstGrid, bad));
            assertTrue(service.enqueue(firstGrid, good));
            assertTrue(service.enqueue(secondGrid, good));
            service.flush();
            for (StableKey grid : Arrays.asList(firstGrid, secondGrid)) {
                List<SqliteCraftingHistoryStore.Summary> saved = service.list(grid, null, 10)
                    .entries();
                assertEquals(1, saved.size());
                assertEquals(
                    good,
                    service.read(
                        grid,
                        saved.get(0)
                            .id()));
            }
            service.close();
        }
    }

    @Test
    void malformedDescriptionsAndProviderReferencesAreRejectedWithoutBlockingTheQueue() throws Exception {
        StableKey grid = StableKey.random();
        CompletedCrafting good = simpleJob(System.currentTimeMillis(), 1);
        List<CompletedCrafting> rejected = new ArrayList<>();
        for (StableKey key : Arrays.asList(null, StableKey.random())) {
            for (ResourceDescriptor description : Arrays.asList(
                new ResourceDescriptor(text(4097), "item", 0, 0, "Product", ResourceType.ITEM, key, null),
                new ResourceDescriptor("test", text(16385), 0, 0, "Product", ResourceType.ITEM, key, null),
                new ResourceDescriptor("test", "item", 0, 0, text(1048577), ResourceType.ITEM, key, null))) {
                rejected.add(
                    new CompletedCrafting(
                        new ResourceSnapshot(description, 1),
                        good.timeStarted(),
                        good.timeDone(),
                        false,
                        good.resources(),
                        good.providers()));
            }
        }
        for (List<String> links : Arrays
            .asList(Collections.singletonList("Missing"), Arrays.asList("Machines", "Machines"))) {
            rejected.add(
                new CompletedCrafting(
                    good.finalOutput(),
                    good.timeStarted(),
                    good.timeDone(),
                    false,
                    Collections.singletonList(
                        new CompletedCrafting.Resource(good.finalOutput(), 0, Collections.emptyList(), links)),
                    good.providers()));
        }
        for (List<CompletedCrafting.Provider> providers : Arrays.asList(
            Arrays.asList(
                good.providers()
                    .get(0),
                good.providers()
                    .get(0)),
            Collections.singletonList(
                new CompletedCrafting.Provider(text(1048577), Collections.emptyList(), Collections.emptyList())),
            Collections.singletonList(
                new CompletedCrafting.Provider(
                    "Machines",
                    Collections.singletonList(new DimensionalCoords(text(4097), 0, 0, 0)),
                    Collections.emptyList())))) {
            rejected.add(
                new CompletedCrafting(
                    good.finalOutput(),
                    good.timeStarted(),
                    good.timeDone(),
                    false,
                    good.resources(),
                    providers));
        }
        rejected.add(
            new CompletedCrafting(
                good.finalOutput(),
                good.timeStarted(),
                good.timeDone(),
                false,
                Collections.singletonList(
                    new CompletedCrafting.Resource(
                        new ResourceSnapshot(
                            good.finalOutput()
                                .description(),
                            -1),
                        0,
                        Collections.emptyList(),
                        Collections.emptyList())),
                good.providers()));
        try (WorldDatabase database = database()) {
            register(database, grid);
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            CraftingHistoryService service = new CraftingHistoryService(store, task -> {});
            for (CompletedCrafting bad : rejected) {
                assertThrows(IOException.class, () -> store.append(grid, bad));
                assertTrue(service.enqueue(grid, bad));
            }
            assertTrue(
                store.list(grid, null, 10)
                    .entries()
                    .isEmpty());
            assertTrue(service.enqueue(grid, good));
            service.close();
            List<SqliteCraftingHistoryStore.Summary> saved = service.list(grid, null, 10)
                .entries();
            assertEquals(1, saved.size());
            assertEquals(
                good,
                service.read(
                    grid,
                    saved.get(0)
                        .id()));
        }
    }

    private static String text(int length) {
        char[] characters = new char[length];
        Arrays.fill(characters, 'x');
        return new String(characters);
    }

    @Test
    void prerequisiteConstraintFailureRetainsValidCompletionUntilGridFlushRecovers() throws Exception {
        StableKey grid = StableKey.random();
        CompletedCrafting job = simpleJob(System.currentTimeMillis(), 1);
        try (WorldDatabase database = database()) {
            register(database, grid);
            database.write(db -> {
                try (Statement sql = db.createStatement()) {
                    sql.executeUpdate(
                        "CREATE TRIGGER test_grid_name BEFORE INSERT ON grids WHEN NEW.name = 'Rejected' BEGIN SELECT RAISE(ABORT, 'Test write failure'); END");
                }
                return null;
            });
            GridIdentityRegistry identities = new GridIdentityRegistry();
            identities.initialize(database, directory.resolve("absent.json"));
            GridPersistentData identity = identities.getPersistentData(grid);
            assertNotNull(identity);
            identity.getSettings()
                .setName("Rejected");
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            CraftingHistoryService service = new CraftingHistoryService(store, task -> {});
            CompletedCrafting invalid = new CompletedCrafting(
                new ResourceSnapshot(
                    job.finalOutput()
                        .description(),
                    -1),
                job.timeStarted(),
                job.timeDone(),
                false,
                job.resources(),
                job.providers());
            IOException rejection = assertThrows(IOException.class, () -> store.append(grid, invalid));
            assertNull(rejection.getCause(), "Malformed payload must be rejected before flushing grid identities");
            assertTrue(service.enqueue(grid, invalid));
            assertTrue(service.enqueue(grid, job));
            IOException failure = assertThrows(IOException.class, service::flush);
            assertTrue(failure.getCause() instanceof SQLException);
            assertEquals(19, ((SQLException) failure.getCause()).getErrorCode() & 0xff);
            assertTrue(
                service.list(grid, null, 10)
                    .entries()
                    .isEmpty());
            identity.getSettings()
                .setName("Recovered");
            service.close();
            List<SqliteCraftingHistoryStore.Summary> saved = service.list(grid, null, 10)
                .entries();
            assertEquals(1, saved.size());
            assertEquals(
                job,
                service.read(
                    grid,
                    saved.get(0)
                        .id()));
            assertFalse(service.enqueue(grid, job));
        }
    }

    @Test
    void rejectedJobsReleaseBoundedQueueCapacityAndCloseDrainsLaterWork() throws Exception {
        StableKey grid = StableKey.random();
        CompletedCrafting good = simpleJob(System.currentTimeMillis(), 1);
        CompletedCrafting bad = new CompletedCrafting(
            new ResourceSnapshot(
                good.finalOutput()
                    .description(),
                -1),
            good.timeStarted(),
            good.timeDone(),
            false,
            good.resources(),
            good.providers());
        try (WorldDatabase database = database()) {
            register(database, grid);
            List<Runnable> scheduled = new ArrayList<>();
            CraftingHistoryService service = new CraftingHistoryService(
                new SqliteCraftingHistoryStore(database),
                scheduled::add);
            for (int index = 0; index < 64; index++) assertTrue(service.enqueue(grid, bad));
            assertFalse(service.enqueue(grid, good));
            scheduled.remove(0)
                .run();
            assertTrue(service.enqueue(grid, good));
            service.close();
            for (Runnable worker : scheduled) worker.run();
            assertEquals(
                1,
                service.list(grid, null, 10)
                    .entries()
                    .size());
            assertFalse(service.enqueue(grid, good));
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
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
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
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            long first = store.append(grid, simpleJob(now - 2, 1));
            long second = store.append(grid, simpleJob(now - 1, 2));
            long third = store.append(grid, simpleJob(now, 3));
            assertNull(store.read(grid, first));
            SqliteCraftingHistoryStore.Page page = store.list(grid, null, 1);
            assertEquals(
                third,
                page.entries()
                    .get(0)
                    .id());
            assertEquals(third, page.nextBefore());
            SqliteCraftingHistoryStore.Page next = store.list(grid, page.nextBefore(), 1);
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
    void prunedJobCursorsAreNeverReusedAfterTheLastOrHighestJobIsRemoved() throws Exception {
        StableKey grid = StableKey.random();
        long now = System.currentTimeMillis();
        long firstRemoved;
        try (WorldDatabase database = database()) {
            register(database, grid);
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            firstRemoved = store.append(grid, simpleJob(now - TimeUnit.DAYS.toMillis(20), 1));
            assertTrue(
                store.list(grid, null, 10)
                    .entries()
                    .isEmpty());
        }
        long highestRemoved;
        try (WorldDatabase database = database()) {
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            long current = store.append(grid, simpleJob(now, 2));
            assertTrue(current > firstRemoved);
            assertNull(store.read(grid, firstRemoved));
            highestRemoved = store.append(grid, simpleJob(now - TimeUnit.DAYS.toMillis(20), 3));
            assertTrue(highestRemoved > current);
            assertNull(store.read(grid, highestRemoved));
        }
        try (WorldDatabase database = database()) {
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            long latest = store.append(grid, simpleJob(now, 4));
            assertTrue(latest > highestRemoved);
            assertEquals(
                latest,
                store.list(grid, null, 1)
                    .entries()
                    .get(0)
                    .id());
            assertNull(store.read(grid, highestRemoved));
        }
    }

    @Test
    void expiredJobsAndFailedWritesDoNotPublishPartialHistory() throws Exception {
        StableKey grid = StableKey.random();
        long now = System.currentTimeMillis();
        try (WorldDatabase database = database()) {
            register(database, grid);
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
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
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
            long id = store.append(grid, job);
            SqliteStorageHistoryStore stock = new SqliteStorageHistoryStore(database);
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
            SqliteCraftingHistoryStore store = new SqliteCraftingHistoryStore(database);
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
        register(database, grid, 0);
    }

    private void register(WorldDatabase database, StableKey grid, int x) throws Exception {
        WorldGridStore store = new WorldGridStore(database);
        store.load(directory.resolve("absent.json"));
        store.save(
            Collections.emptyMap(),
            Collections.singletonMap(
                grid,
                new GridPersistentData(
                    Collections.singleton(new DimensionalCoords("overworld", x, 0, 0)),
                    new GridSettingsData(database.policy()))),
            Collections.singleton(grid));
    }
}
