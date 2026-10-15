package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetItems;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.InMemoryStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistory;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StoragePublicationListener;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

class StorageTrackingCoordinatorTest extends GridTestScope {

    @Test
    void disablingFlowReleasesRetiredIdentitiesWhileTheGridRemainsLive() throws Exception {
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710010);
        StableKey gridKey = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(gridKey);
        assertNotNull(data);
        data.getSettings()
            .setFlowTracked(true);
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking())) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            StableKey itemKey = capture.getItemKey(new ItemIdentityRequestTest.Resource("transient-flow", 1, false));
            assertNotNull(itemKey);
            capture.recordFlow(itemKey, 1, 1);
            WeakReference<IAEKey> identity = new WeakReference<>(AE2Controller.itemIdentities.resolve(itemKey));
            assertNotNull(identity.get());
            data.getSettings()
                .setFlowTracked(false);
            coordinator.tick(TestGridFixtures.ae(grid), 2000);
            assertNull(grid.web$getStorageCapture());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (identity.get() != null && System.nanoTime() < deadline) {
                System.gc();
                AE2Controller.itemIdentities.cleanUp();
                Thread.sleep(10);
            }
            assertNull(
                identity.get(),
                "Retired flow must not retain native identities for the grid's remaining lifetime");
            assertNull(grid.web$getStorageCapture());
        }
    }

    @Test
    void inventoryListingRetainsRemovedResourcesUntilTheirFlowIsFlushed() throws Exception {
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("removed-iron", 20, false);
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710010, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setFlowTracked(true);
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking())) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            capture.recordFlow(capture.getItemKey(iron), 0, 20);
            grid.rows.clear();
            GetItems request = new GetItems();
            assertTrue(request.init(TestGridFixtures.context(-1, "grid=" + key)));
            request.runOnServerThread(TestGridFixtures.ae(grid));
            assertEquals(
                HttpURLConnection.HTTP_OK,
                request.getResponse()
                    .httpStatus());
            System.gc();
            AE2Controller.itemIdentities.cleanUp();
            coordinator.tick(TestGridFixtures.ae(grid), 6000);
            service.close();
            assertTrue(
                store.read(key, iron.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .anyMatch(point -> point.input() == 0 && point.output() == 20));
        }
    }

    @Test
    void asynchronousFailureReconcilesAtNextViewerRead() throws Exception {
        assertAsynchronousRecovery(true);
    }

    @Test
    void asynchronousFailureBeforeFirstPublicationReconcilesAtNextViewerRead() throws Exception {
        assertAsynchronousRecovery(false);
    }

    private void assertAsynchronousRecovery(boolean afterPublication) throws Exception {
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("recovery", 4, false);
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710009, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setStorageTracked(true);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        CountDownLatch invalidated = new CountDownLatch(1);
        StoragePublicationListener listener = new StoragePublicationListener() {

            public void published(@NotNull StableKey gridKey, @NotNull StorageSnapshot snapshot) {
                if (afterPublication && failOnce.getAndSet(false))
                    throw new IllegalStateException("Detached publication failed");
            }

            public void retired(@NotNull StableKey gridKey, long generation) {
                invalidated.countDown();
            }
        };
        IStorageHistoryStore store = new IStorageHistoryStore() {

            public void append(@NotNull StableKey gridKey, @NotNull List<StorageHistoryPoint> points) {
                if (!afterPublication && failOnce.getAndSet(false))
                    throw new IllegalStateException("Detached append failed");
            }

            public @NotNull StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey item, long from,
                int limit) {
                return new StorageHistory(Collections.emptyList(), true, false);
            }
        };
        try (StorageTrackingService service = new StorageTrackingService(store, 100, listener);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking())) {
            assertNotNull(coordinator.observeForViewer(grid, 1000));
            assertTrue(invalidated.await(3, TimeUnit.SECONDS));
            grid.rows.clear();
            grid.rows.add(new ItemIdentityRequestTest.Resource("recovery", 9, false));
            coordinator.observeForViewer(grid, 1500);
            StorageSnapshot recovered = awaitRevision(service, key, afterPublication ? 2 : 1);
            assertTrue(recovered.stockComplete(), "The next scheduled read must reconcile a failed detached batch");
            assertEquals(
                9,
                recovered.rows()
                    .get(0)
                    .resource()
                    .quantity());
        }
    }

    @Test
    void pendingWorkerBaselineIsSharedByImmediateViewerRequests() throws Exception {
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("baseline", 4, false);
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710008, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setStorageTracked(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IStorageHistoryStore blocked = new IStorageHistoryStore() {

            public void append(@NotNull StableKey gridKey, @NotNull List<StorageHistoryPoint> points)
                throws IOException {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Test writer did not resume");
                } catch (InterruptedException failure) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IOException(failure);
                }
            }

            public @NotNull StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey resource, long from,
                int limit) {
                return new StorageHistory(Collections.emptyList(), true, false);
            }
        };
        try (StorageTrackingService service = new StorageTrackingService(blocked, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking())) {
            try {
                assertNotNull(coordinator.observeForViewer(grid, 1000));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                StorageSnapshot pending = coordinator.observeForViewer(grid, 1001);
                assertNotNull(pending, "A pending detached baseline must not fall back to another native list read");
                assertEquals(
                    4,
                    pending.rows()
                        .get(0)
                        .resource()
                        .quantity());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void viewerLeaseDoesNotReadNativeStateAndIconDemandEnrichesSharedSnapshot() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        AtomicInteger bases = new AtomicInteger();
        StableKey base = StableKey.random();
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("icon-iron", 20, false) {

            public @NotNull IAEKey web$copyIdentity() {
                copies.incrementAndGet();
                return this;
            }

            public StableKey web$getIconBaseKey() {
                bases.incrementAndGet();
                return base;
            }
        };
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710007, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        Path archive;
        IconPack.Metadata metadata = new IconPack.Metadata(
            "1.7.10",
            "forge",
            "test",
            "test",
            "today",
            Collections.emptyMap(),
            Collections.emptyList());
        try (IconPackWriter writer = new IconPackWriter(
            gridSave.toPath()
                .resolve("icons"),
            metadata,
            64)) {
            int[] pixels = new int[4096];
            Arrays.fill(pixels, 0xff112233);
            writer.add(base, pixels);
            archive = writer.finish();
        }
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        try (IconPack pack = IconPack.open(archive, "1.7.10", "forge", "test");
            StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings,
                () -> pack)) {
            coordinator.touchViewer(grid, 1000, false);
            assertEquals(0, copies.get());
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            StorageSnapshot first = awaitRevision(service, key, 1);
            assertNull(
                first.rows()
                    .get(0)
                    .resource()
                    .iconBaseKey());
            assertEquals(0, bases.get());
            coordinator.touchViewer(grid, 1250, true);
            assertEquals(0, bases.get());
            coordinator.tick(TestGridFixtures.ae(grid), 1250);
            StorageSnapshot enriched = awaitRevision(service, key, 2);
            assertEquals(
                base,
                enriched.rows()
                    .get(0)
                    .resource()
                    .iconBaseKey());
            assertEquals(1, bases.get());
        }
    }

    @Test
    void activeViewersDoNotIncreaseStockSamplingOrSplitFlowBuckets() throws Exception {
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("iron", 20, false);
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710006, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setStorageTracked(true);
        data.getSettings()
            .setFlowTracked(true);
        data.getSettings()
            .setStockSampleIntervalSeconds(10);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            awaitRevision(service, key, 1);
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            capture.recordFlow(capture.getItemKey(iron), 7, 0);
            grid.rows.clear();
            grid.rows.add(new ItemIdentityRequestTest.Resource("iron", 27, false));
            coordinator.observeForViewer(grid, 2000);
            awaitRevision(service, key, 2);
            coordinator.tick(TestGridFixtures.ae(grid), 6000);
            coordinator.observeForViewer(grid, 7000);
            coordinator.tick(TestGridFixtures.ae(grid), 11000);
            service.close();
            List<StorageHistoryPoint> points = store.read(key, iron.web$getKey(), 0, 100)
                .points();
            assertEquals(
                2,
                points.stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                    .count());
            assertTrue(
                points.stream()
                    .noneMatch(point -> point.observedAt() == 2000 || point.observedAt() == 7000));
            assertTrue(
                points.stream()
                    .anyMatch(
                        point -> point.kind() == StorageHistoryPoint.Kind.FLOW && point.observedAt() == 6000
                            && point.input() == 7));
            assertTrue(
                points.stream()
                    .anyMatch(
                        point -> point.kind() == StorageHistoryPoint.Kind.STOCK && point.observedAt() == 11000
                            && point.resource() != null
                            && point.resource()
                                .quantity() == 27));
        }
    }

    @Test
    void flowOnlyTrackingNeverReadsStockOrCraftables() throws Exception {
        StorageCapture[] capture = new StorageCapture[1];
        IAEStorageGrid storage = new IAEStorageGrid() {

            public IStackList web$getStorageList() {
                throw new AssertionError("Flow-only tracking must not enumerate storage");
            }

            public IAEMeInventoryItem web$getInventory() {
                return null;
            }

            public StorageCapture web$getStorageCapture() {
                return capture[0];
            }

            public void web$setStorageCapture(StorageCapture value) {
                capture[0] = value;
            }
        };
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(
            710005,
            false,
            AEControllerState.CONTROLLER_ONLINE) {

            public IAEStorageGrid web$getStorageGrid() {
                return storage;
            }

            public IAECraftingGrid web$getCraftingGrid() {
                throw new AssertionError("Flow-only tracking must not read craftables");
            }
        };
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setFlowTracked(true);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            assertNotNull(capture[0]);
            ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("iron", 20, false);
            capture[0].recordFlow(capture[0].getItemKey(iron), 7, 3);
            coordinator.tick(TestGridFixtures.ae(grid), 6000);
            service.close();
            assertTrue(
                store.read(key, iron.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .anyMatch(point -> point.input() == 7 && point.output() == 3));
        }
    }

    @Test
    void failedViewerReadsPreserveCauseAndBackoffAcrossLeaseRenewal() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean failing = new AtomicBoolean(true);
        IllegalStateException cause = new IllegalStateException("Broken native identity capture");
        ItemIdentityRequestTest.Resource broken = new ItemIdentityRequestTest.Resource("broken", 1, false) {

            @Override
            public @NotNull IAEKey web$copyIdentity() {
                attempts.incrementAndGet();
                if (failing.get()) throw cause;
                return super.web$copyIdentity();
            }
        };
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710003, broken);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
            assertSame(
                cause,
                assertThrows(IllegalStateException.class, () -> coordinator.observeForViewer(grid, 1000)));
            assertSame(
                cause,
                assertThrows(IllegalStateException.class, () -> coordinator.observeForViewer(grid, 1500)));
            coordinator.tick(TestGridFixtures.ae(grid), 12000);
            assertSame(
                cause,
                assertThrows(IllegalStateException.class, () -> coordinator.observeForViewer(grid, 13000)));
            assertEquals(1, attempts.get(), "renewing an expired viewer lease must not bypass failure backoff");
            failing.set(false);
            assertNotNull(coordinator.observeForViewer(grid, 33000));
            assertEquals(2, attempts.get());
            assertFalse(
                awaitRevision(service, key, 1).rows()
                    .isEmpty());
        }
    }

    @Test
    void failedIdleObservationRetriesAtIdleCadenceInsteadOfRescanningEveryTick() {
        AtomicInteger attempts = new AtomicInteger();
        ItemIdentityRequestTest.Resource unsupported = new ItemIdentityRequestTest.Resource("unsupported", 1, false) {

            @Override
            public @NotNull IAEKey web$copyIdentity() {
                attempts.incrementAndGet();
                throw new IllegalStateException("Broken native capture");
            }
        };
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710002, unsupported);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData persistentData = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(persistentData);
        persistentData.getSettings()
            .setStorageTracked(true);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            assertEquals(1, attempts.get());
            coordinator.tick(TestGridFixtures.ae(grid), 2000);
            coordinator.tick(TestGridFixtures.ae(grid), 3000);
            assertEquals(1, attempts.get());
            coordinator.tick(
                TestGridFixtures.ae(grid),
                2000 + TimeUnit.SECONDS.toMillis(
                    persistentData.getSettings()
                        .getStockSampleIntervalSeconds()));
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void enabledGridRecordsStockAndFlowWhileViewerLeaseChangesOnlyCadence() throws Exception {
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("iron", 20, false);
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710001, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData persistentData = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(persistentData);
        persistentData.getSettings()
            .setStorageTracked(true);
        persistentData.getSettings()
            .setFlowTracked(true);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
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
                        .getItemKey(iron),
                    7,
                    3);
            coordinator.tick(TestGridFixtures.ae(grid), 2000);
            StorageSnapshot idle = service.snapshot(key);
            assertNotNull(idle);
            assertEquals(1, idle.revision(), "idle tracking must not read storage every tick");
            coordinator.observeForViewer(grid, 2000);
            awaitRevision(service, key, 2);
            assertTrue(
                service.history(key, iron.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .noneMatch(point -> point.input() != 0 || point.output() != 0));
            coordinator.tick(TestGridFixtures.ae(grid), 6000);
            awaitRevision(service, key, 3);
            assertTrue(
                service.history(key, iron.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .anyMatch(point -> point.input() == 7 && point.output() == 3));
            persistentData.getSettings()
                .setFlowTracked(false);
            persistentData.getSettings()
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

    private static @NotNull StorageSnapshot awaitRevision(StorageTrackingService service, StableKey key, long revision)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            StorageSnapshot result = service.snapshot(key);
            if (result != null && result.revision() >= revision) return result;
            Thread.sleep(5);
        }
        return fail("Observation was not published");
    }
}
