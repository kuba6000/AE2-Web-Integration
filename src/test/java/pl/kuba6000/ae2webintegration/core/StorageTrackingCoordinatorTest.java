package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.storage.FileStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

class StorageTrackingCoordinatorTest extends GridTestScope {

    @Test
    void failedIdleObservationRetriesAtIdleCadenceInsteadOfRescanningEveryTick() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        ItemIdentityRequestTest.Resource unsupported = new ItemIdentityRequestTest.Resource("unsupported", 1, false) {

            @Override
            public pl.kuba6000.ae2webintegration.core.interfaces.IAEKey web$copyTrackingIdentity() {
                attempts.incrementAndGet();
                throw new UnsupportedOperationException();
            }
        };
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710002, unsupported);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings()
            .setStorageTracked(true);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        try (StorageTrackingService service = new StorageTrackingService(
            new FileStorageHistoryStore(
                gridSave.toPath()
                    .resolve("history"),
                1000000),
            100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            assertEquals(1, attempts.get());
            coordinator.tick(TestGridFixtures.ae(grid), 2000);
            coordinator.tick(TestGridFixtures.ae(grid), 3000);
            assertEquals(1, attempts.get());
            coordinator.tick(TestGridFixtures.ae(grid), 1000 + TimeUnit.SECONDS.toMillis(settings.idleIntervalSeconds));
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void enabledGridRecordsStockAndFlowWhileViewerLeaseChangesOnlyCadence() throws Exception {
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("iron", 20, false);
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710001, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings()
            .setStorageTracked(true);
        Path directory = gridSave.toPath()
            .resolve("history");
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        try (
            StorageTrackingService service = new StorageTrackingService(
                new FileStorageHistoryStore(directory, 1000000),
                100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            assertNotNull(grid.web$getStorageCapture());
            StorageSnapshot first = awaitRevision(service, key, 1);
            assertEquals(
                20,
                first.rows()
                    .get(0)
                    .resource()
                    .quantity());
            grid.web$getStorageCapture()
                .recordFlow(
                    grid.web$getStorageCapture()
                        .prepareResource(iron),
                    7,
                    3);
            coordinator.tick(TestGridFixtures.ae(grid), 2000);
            assertEquals(
                1,
                service.snapshot(key)
                    .revision(),
                "idle tracking must not read storage every tick");
            coordinator.observeForViewer(grid, 2000);
            awaitRevision(service, key, 2);
            assertTrue(
                service.history(key, iron.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .anyMatch(point -> point.input() == 7 && point.output() == 3));
            CoreEngine.GRID_IDENTITIES.getPersistentData(key)
                .getSettings()
                .setStorageTracked(false);
            coordinator.tick(TestGridFixtures.ae(grid), 20000);
            assertNull(grid.web$getStorageCapture(), "disabled tracking without viewers releases native capture");
            assertNull(service.snapshot(key));
            assertFalse(
                service.history(key, null, 0, 100)
                    .points()
                    .isEmpty(),
                "disabling does not erase history");
        }
    }

    private static StorageSnapshot awaitRevision(StorageTrackingService service, StableKey key, long revision)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            StorageSnapshot result = service.snapshot(key);
            if (result != null && result.revision() >= revision) return result;
            Thread.sleep(5);
        }
        fail("Observation was not published");
        return null;
    }
}
