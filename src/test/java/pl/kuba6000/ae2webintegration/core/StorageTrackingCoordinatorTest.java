package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetItems;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.InMemoryStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistory;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

class StorageTrackingCoordinatorTest extends GridTestScope {

    @Test
    void pendingTerminalHandoffRenewsCaptureWithoutTransportTickOrHistory() throws Exception {
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("viewer-demand", 10, false);
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710019, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        TerminalRegistry terminals = new TerminalRegistry();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            coordinator.prepareForViewer(grid, 1000);
            AuthSession login = new AuthSession(WebPrincipal.admin(), Long.MAX_VALUE);
            assertNotNull(terminals.reserve(key, login, false, null, 1000));
            coordinator.tick(TestGridFixtures.ae(grid), 9000);
            grid.rows.set(0, new ItemIdentityRequestTest.Resource("viewer-demand", 25, false));
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            capture.markChanged(iron);
            coordinator.tick(TestGridFixtures.ae(grid), 12000);
            StorageSnapshot snapshot = terminals.snapshot(key);
            assertNotNull(snapshot);
            assertEquals(
                25,
                snapshot.rows()
                    .get(0)
                    .quantity());
            assertNull(service.snapshot(key));
            coordinator.tick(TestGridFixtures.ae(grid), 20000);
            assertNull(grid.web$getStorageCapture());
        }
    }

    @Test
    void topologyChangeDuringNativeReadCannotPublishThatBaseline() throws Exception {
        AtomicBoolean change = new AtomicBoolean();
        StorageCapture[] capture = new StorageCapture[1];
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("reentrant-topology", 4, false) {

            @Override
            public @NotNull IAEKey web$copyIdentity() {
                return this;
            }

            @Override
            public @NotNull String web$getDisplayName() {
                if (change.getAndSet(false)) capture[0].invalidateTerminal();
                return super.web$getDisplayName();
            }
        };
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710012, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking())) {
            coordinator.touchViewer(grid, 1000);
            capture[0] = grid.web$getStorageCapture();
            change.set(true);
            assertNull(
                coordinator.observeForViewer(grid, 1000),
                "An observation crossing topology reset cannot bootstrap a client");
            StorageSnapshot fresh = coordinator.observeForViewer(grid, 1500);
            assertNotNull(fresh);
            assertEquals(
                4,
                fresh.rows()
                    .get(0)
                    .quantity());
            assertNull(service.snapshot(key), "Viewer-only observation must not create history state");
        }
    }

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
            IAEKey resource = new ItemIdentityRequestTest.Resource("transient-flow", 1, false);
            StableKey itemKey = resource.web$getKey();
            capture.recordFlow(resource, 1, 1);
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
            capture.recordFlow(iron, 0, 20);
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
    void blockedHistoryDoesNotDelayLiveSnapshotOrChanges() throws Exception {
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("baseline", 4, false);
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710008, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setStorageTracked(true);
        data.getSettings()
            .setFlowTracked(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IStorageHistoryStore blocked = new IStorageHistoryStore() {

            public void append(@NotNull StableKey gridKey, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
                @NotNull List<StorageHistoryPoint> points) throws IOException {
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
                return new StorageHistory(Collections.emptyList(), Collections.emptyMap(), true, false);
            }
        };
        try (StorageTrackingService service = new StorageTrackingService(blocked, 2);
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
                        .quantity());
                grid.rows.clear();
                grid.rows.add(new ItemIdentityRequestTest.Resource("baseline", 11, false));
                StorageCapture capture = grid.web$getStorageCapture();
                assertNotNull(capture);
                capture.recordFlow(iron, 7, 0);
                StorageSnapshot updated = coordinator.observeForViewer(grid, 1500);
                assertNotNull(updated);
                assertEquals(
                    11,
                    updated.rows()
                        .get(0)
                        .quantity());
                coordinator.tick(TestGridFixtures.ae(grid), 6000);
                assertFalse(
                    capture.requiresReconciliation(),
                    "History queue rejection must not force a terminal rescan");
                grid.rows.set(0, new ItemIdentityRequestTest.Resource("baseline", 12, false));
                capture.markChanged(iron);
                StorageSnapshot afterRejectedHistory = coordinator.observeForViewer(grid, 6500);
                assertNotNull(afterRejectedHistory);
                assertEquals(
                    12,
                    afterRejectedHistory.rows()
                        .get(0)
                        .quantity());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void viewerLeaseDefersMetadataAndDescriptionAlwaysContainsBaseKey() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        AtomicInteger bases = new AtomicInteger();
        StableKey base = StableKey.random();
        ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("icon-iron", 20, false) {

            public @NotNull IAEKey web$copyIdentity() {
                copies.incrementAndGet();
                return this;
            }

            public StableKey web$getBaseKey() {
                bases.incrementAndGet();
                return base;
            }
        };
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(710007, iron);
        TestGridFixtures.resolvedKey(grid);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
            coordinator.touchViewer(grid, 1000);
            assertEquals(0, copies.get());
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            StorageSnapshot first = coordinator.observeForViewer(grid, 1000);
            assertNotNull(first);
            assertEquals(
                base,
                first.descriptions()
                    .get(iron.web$getKey())
                    .baseKey());
            assertEquals(1, bases.get());
            coordinator.touchViewer(grid, 1250);
            coordinator.tick(TestGridFixtures.ae(grid), 1250);
            StorageSnapshot next = coordinator.observeForViewer(grid, 1500);
            assertNotNull(next);
            assertSame(
                first.descriptions()
                    .get(iron.web$getKey()),
                next.descriptions()
                    .get(iron.web$getKey()));
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
            capture.recordFlow(iron, 7, 0);
            grid.rows.clear();
            grid.rows.add(new ItemIdentityRequestTest.Resource("iron", 27, false));
            coordinator.observeForViewer(grid, 2000);
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
                            && point.itemKey() != null
                            && point.quantity() == 27));
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
            capture[0].recordFlow(iron, 7, 3);
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
        TestGridFixtures.resolvedKey(grid);
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
            var recovered = coordinator.observeForViewer(grid, 41000);
            assertNotNull(recovered);
            assertFalse(
                recovered.rows()
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
                    .quantity());
            grid.web$getStorageCapture()
                .recordFlow(iron, 7, 3);
            coordinator.tick(TestGridFixtures.ae(grid), 2000);
            StorageSnapshot idle = service.snapshot(key);
            assertNotNull(idle);
            assertEquals(1, idle.revision(), "idle tracking must not read storage every tick");
            coordinator.observeForViewer(grid, 2000);
            assertTrue(
                service.history(key, iron.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .noneMatch(point -> point.input() != 0 || point.output() != 0));
            coordinator.tick(TestGridFixtures.ae(grid), 6000);
            awaitRevision(service, key, 2);
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
