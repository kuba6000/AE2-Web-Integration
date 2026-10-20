package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pl.kuba6000.ae2webintegration.core.ItemIdentityRequestTest.Grid;
import pl.kuba6000.ae2webintegration.core.ItemIdentityRequestTest.Resource;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetItems;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.InMemoryStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity.Amount;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity.Summary;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistory;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

class StorageTrackingCoordinatorTest extends GridTestScope {

    private ConfigSettings previousConfig;

    @Override
    @BeforeEach
    void initializeGridSave() throws IOException {
        previousConfig = Config.INSTANCE;
        ConfigSettings configured = new ConfigSettings();
        // Short simulated intervals exercise scheduling independently of administrator defaults.
        configured.resourceHistory.stockSampleIntervalSeconds.min = 1;
        configured.resourceHistory.stockSampleIntervalSeconds.max = 7200;
        configured.resourceHistory.flowFlushIntervalSeconds.min = 1;
        Config.INSTANCE = configured;
        super.initializeGridSave();
    }

    @Override
    @AfterEach
    void clearGridSave() {
        super.clearGridSave();
        Config.INSTANCE = previousConfig;
    }

    @Test
    void failedEnumerationRetainsCaptureAndObservationsUntilACompleteReconciliation() throws Exception {
        Resource iron = new Resource("enumeration-iron", 20, false);
        Grid grid = new Grid(710041, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        GridSettingsData policy = data.getSettings();
        policy.setStorageTracked(true);
        policy.setStockSampleIntervalSeconds(1);
        AtomicInteger enumerations = new AtomicInteger();
        AtomicBoolean failEnumeration = new AtomicBoolean(true);
        TestGridFixtures.TestAE unreliable = new TestGridFixtures.TestAE() {

            @Override
            public Iterable<IAEGrid> web$getGrids() {
                enumerations.incrementAndGet();
                if (!failEnumeration.get()) return Collections.emptyList();
                return () -> new Iterator<IAEGrid>() {

                    private boolean first = true;

                    public boolean hasNext() {
                        if (!first) throw new IllegalStateException("Native enumeration interrupted");
                        return true;
                    }

                    public IAEGrid next() {
                        first = false;
                        return grid;
                    }
                };
            }
        };
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking())) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            awaitRevision(service, key, 1);
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            grid.rows.set(0, new Resource("enumeration-iron", 25, false));
            capture.markChanged(iron);
            assertDoesNotThrow(() -> coordinator.tick(unreliable, 2000));
            assertSame(capture, grid.web$getStorageCapture());
            assertEquals(
                25,
                awaitRevision(service, key, 2).rows()
                    .get(0)
                    .quantity());
            coordinator.tick(unreliable, 3000);
            assertEquals(1, enumerations.get(), "A failed enumeration must back off without stopping observations");
            failEnumeration.set(false);
            coordinator.tick(unreliable, 34000);
            assertNull(grid.web$getStorageCapture());
            assertNull(service.snapshot(key));
        }
    }

    @Test
    void failedEligibilityPreservesItsBindingWhileHealthyAndRetiredGridsProgress() throws Exception {
        Resource iron = new Resource("eligibility-iron", 20, false);
        FaultGrid failing = new FaultGrid(710051, iron);
        Grid healthy = new Grid(710052, iron);
        Grid retired = new Grid(710053, iron);
        StableKey failingKey = trackStock(failing);
        StableKey healthyKey = trackStock(healthy);
        StableKey retiredKey = trackStock(retired);
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking())) {
            coordinator.prepareForViewer(failing, 1000);
            coordinator.prepareForViewer(healthy, 1000);
            coordinator.prepareForViewer(retired, 1000);
            awaitRevision(service, failingKey, 1);
            awaitRevision(service, healthyKey, 1);
            awaitRevision(service, retiredKey, 1);
            StorageCapture capture = failing.capture;
            failing.failEligibility = true;
            healthy.rows.set(0, new Resource("eligibility-iron", 25, false));
            StorageCapture healthyCapture = healthy.web$getStorageCapture();
            assertNotNull(healthyCapture);
            healthyCapture.markChanged(iron);
            assertDoesNotThrow(() -> coordinator.tick(TestGridFixtures.ae(failing, healthy), 2000));
            assertSame(capture, failing.capture);
            StorageSnapshot failedSnapshot = service.snapshot(failingKey);
            assertNotNull(failedSnapshot);
            assertFalse(failedSnapshot.stockComplete());
            assertNull(service.snapshot(retiredKey));
            assertNull(retired.web$getStorageCapture());
            assertEquals(
                25,
                awaitRevision(service, healthyKey, 2).rows()
                    .get(0)
                    .quantity());
            int attempts = failing.eligibilityReads;
            coordinator.tick(TestGridFixtures.ae(failing, healthy), 3000);
            assertEquals(attempts, failing.eligibilityReads, "Failed eligibility must honor the binding retry delay");
            failing.failEligibility = false;
            coordinator.prepareForViewer(failing, 34000);
            assertSame(capture, failing.capture);
            assertEquals(
                20,
                awaitRevision(service, failingKey, 2).rows()
                    .get(0)
                    .quantity());
        }
    }

    @Test
    void enumerationBackoffStillRenewsViewerDemandBeyondTheCaptureLease() throws Exception {
        Resource iron = new Resource("discovery-viewer", 10, false);
        Grid grid = new Grid(710054, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        AtomicInteger enumerations = new AtomicInteger();
        TestGridFixtures.TestAE failing = new TestGridFixtures.TestAE() {

            @Override
            public Iterable<IAEGrid> web$getGrids() {
                enumerations.incrementAndGet();
                throw new IllegalStateException("Native enumeration unavailable");
            }
        };
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            coordinator.prepareForViewer(grid, 1000);
            AuthSession login = new AuthSession(WebPrincipal.admin(), Long.MAX_VALUE);
            TerminalRegistry.Bootstrap initial = terminals.reserve(key, login, false, null, null, 1000);
            assertNotNull(initial);
            coordinator.tick(failing, 2000);
            assertNotNull(terminals.reserve(key, login, false, null, initial.subscription(), 9000));
            grid.rows.set(0, new Resource("discovery-viewer", 25, false));
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            capture.markChanged(iron);
            coordinator.tick(failing, 13000);
            StorageSnapshot snapshot = terminals.snapshot(key);
            assertNotNull(snapshot);
            assertEquals(
                25,
                snapshot.rows()
                    .get(0)
                    .quantity());
            assertEquals(1, enumerations.get(), "Viewer renewal must not bypass native discovery backoff");
            assertNull(service.snapshot(key), "Viewer demand must not enable history tracking");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "access", "before", "after" })
    void failedAttachmentStaysPendingAndRetriesTheCompleteNativeInstallation(String failurePoint) throws Exception {
        Resource iron = new Resource("attachment-iron", 20, false);
        FaultGrid failing = new FaultGrid(710055, iron);
        Grid healthy = new Grid(710056, iron);
        StableKey failingKey = trackStock(failing);
        StableKey healthyKey = trackStock(healthy);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(failingKey);
        assertNotNull(data);
        GridSettingsData policy = data.getSettings();
        policy.setFlowTracked(true);
        policy.setFlowFlushIntervalSeconds(1);
        failing.attachmentFailure = failurePoint;
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            assertDoesNotThrow(() -> coordinator.tick(TestGridFixtures.ae(failing, healthy), 1000));
            assertNull(service.snapshot(failingKey));
            assertNull(terminals.snapshot(failingKey));
            assertNull(failing.hookCapture);
            assertEquals(
                20,
                awaitRevision(service, healthyKey, 1).rows()
                    .get(0)
                    .quantity());
            StorageCapture partial = failing.capture;
            if (partial != null) partial.recordFlow(iron, 7, 2);
            int accesses = failing.storageAccesses;
            assertThrows(IllegalStateException.class, () -> coordinator.prepareForViewer(failing, 2000));
            coordinator.tick(TestGridFixtures.ae(failing, healthy), 3000);
            assertEquals(accesses, failing.storageAccesses, "An incomplete installation must respect retry backoff");
            assertNull(service.snapshot(failingKey));
            assertNull(terminals.snapshot(failingKey));
            failing.attachmentFailure = null;
            coordinator.tick(TestGridFixtures.ae(failing, healthy), 34000);
            coordinator.tick(TestGridFixtures.ae(failing, healthy), 34001);
            StorageSnapshot recovered = awaitRevision(service, failingKey, 1);
            assertNotNull(failing.capture);
            assertSame(failing.capture, failing.hookCapture);
            if (partial != null) assertSame(partial, failing.capture);
            assertEquals(
                20,
                recovered.rows()
                    .get(0)
                    .quantity());
            assertFalse(recovered.flowComplete(), "Partial native installation must leave an honest coverage gap");
            if (partial != null) assertTrue(
                service.history(failingKey, iron.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .anyMatch(point -> point.input() == 7 && point.output() == 2));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "access", "getter", "before", "after" })
    void nativeDetachFailureStillRetiresOwnedViewsWorkersAndOtherGrids(String failurePoint) throws Exception {
        Resource iron = new Resource("detach-iron", 20, false);
        FaultGrid failing = new FaultGrid(710057, iron);
        FaultGrid healthy = new FaultGrid(710058, iron);
        StableKey failingKey = trackStock(failing);
        StableKey healthyKey = trackStock(healthy);
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            coordinator.prepareForViewer(failing, 1000);
            coordinator.prepareForViewer(healthy, 1000);
            awaitRevision(service, failingKey, 1);
            awaitRevision(service, healthyKey, 1);
            awaitRecent(terminals, failingKey, 1000);
            awaitRecent(terminals, healthyKey, 1000);
            assertNotNull(coordinator.recent(failingKey));
            failing.detachFailure = failurePoint;
            assertDoesNotThrow(() -> coordinator.tick(TestGridFixtures.ae(), 2000));
            assertNull(service.snapshot(failingKey));
            assertNull(service.snapshot(healthyKey));
            assertNull(terminals.snapshot(failingKey));
            assertNull(terminals.snapshot(healthyKey));
            assertNull(coordinator.recent(failingKey));
            assertNull(coordinator.recent(healthyKey));
            assertNull(healthy.capture);
            assertNull(healthy.hookCapture);
        }
    }

    private static StableKey trackStock(IAEGrid grid) {
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        GridSettingsData policy = data.getSettings();
        policy.setStorageTracked(true);
        policy.setStockSampleIntervalSeconds(1);
        return key;
    }

    @Test
    void retiredGridKeepsChosenRetentionForItsFirstPendingFlow() throws Exception {
        Resource item = new Resource("retired-policy", 20, false);
        Grid grid = new Grid(710043, item);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridSettingsData settings = CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings();
        settings.setFlowTracked(true);
        long startedAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10);
        HsqlStorageHistoryStore store = new HsqlStorageHistoryStore(
            worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        try (StorageTrackingService service = new StorageTrackingService(store, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking())) {
            coordinator.tick(TestGridFixtures.ae(grid), startedAt);
            grid.web$getStorageCapture()
                .recordFlow(item, 7, 2);
            settings.setHistoryRetentionDays(14);
            CoreEngine.GRID_IDENTITIES.removeController(
                grid.web$getControllers()
                    .iterator()
                    .next());
            coordinator.tick(TestGridFixtures.ae(), startedAt + 1000);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (store.read(key, item.web$getKey(), 0, 100)
                .points()
                .stream()
                .noneMatch(point -> point.kind() == StorageHistoryPoint.Kind.FLOW) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(
                store.read(key, item.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .anyMatch(
                        point -> point.kind() == StorageHistoryPoint.Kind.FLOW && point.input() == 7
                            && point.output() == 2));
        }
    }

    @Test
    void eachGridUsesItsFlowCadenceAndReloadKeepsPendingCounters() throws Exception {
        Resource item = new Resource("cadence-policy", 20, false);
        Grid fast = new Grid(710041, item);
        Grid slow = new Grid(710042, item);
        StableKey fastKey = TestGridFixtures.resolvedKey(fast);
        StableKey slowKey = TestGridFixtures.resolvedKey(slow);
        GridPersistentData fastData = CoreEngine.GRID_IDENTITIES.getPersistentData(fastKey);
        GridPersistentData slowData = CoreEngine.GRID_IDENTITIES.getPersistentData(slowKey);
        assertNotNull(fastData);
        assertNotNull(slowData);
        GridSettingsData fastSettings = fastData.getSettings();
        GridSettingsData slowSettings = slowData.getSettings();
        fastSettings.setFlowTracked(true);
        slowSettings.setFlowTracked(true);
        fastSettings.setFlowFlushIntervalSeconds(5);
        slowSettings.setFlowFlushIntervalSeconds(20);
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking())) {
            coordinator.prepareForViewer(fast, 1000);
            coordinator.prepareForViewer(slow, 1000);
            StorageCapture fastCapture = fast.web$getStorageCapture();
            StorageCapture slowCapture = slow.web$getStorageCapture();
            assertNotNull(fastCapture);
            assertNotNull(slowCapture);
            fastCapture.recordFlow(item, 7, 2);
            slowCapture.recordFlow(item, 9, 3);
            coordinator.prepareForViewer(fast, 6000);
            coordinator.prepareForViewer(slow, 6000);
            fastCapture.recordFlow(item, 4, 1);
            ConfigSettings.ResourceHistory reloaded = new ConfigSettings.ResourceHistory();
            reloaded.flowFlushIntervalSeconds = new ConfigSettings.Range(10, 300, 10);
            assertTrue(
                worlds.database(TestGridFixtures.Worlds.source(gridSave))
                    .applyPolicy(reloaded));
            coordinator.prepareForViewer(fast, 7000);
            coordinator.prepareForViewer(fast, 16000);
            coordinator.prepareForViewer(slow, 21000);
            service.close();
            List<StorageHistoryPoint> fastFlow = store.read(fastKey, item.web$getKey(), 0, 100)
                .points()
                .stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
                .collect(Collectors.toList());
            List<StorageHistoryPoint> slowFlow = store.read(slowKey, item.web$getKey(), 0, 100)
                .points()
                .stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
                .collect(Collectors.toList());
            assertEquals(2, fastFlow.size());
            assertEquals(
                6000,
                fastFlow.get(0)
                    .observedAt());
            assertEquals(
                7,
                fastFlow.get(0)
                    .input());
            assertEquals(
                6000,
                fastFlow.get(1)
                    .flowFrom());
            assertEquals(
                16000,
                fastFlow.get(1)
                    .observedAt());
            assertEquals(
                4,
                fastFlow.get(1)
                    .input());
            assertEquals(1, slowFlow.size());
            assertEquals(
                21000,
                slowFlow.get(0)
                    .observedAt());
            assertEquals(
                9,
                slowFlow.get(0)
                    .input());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void orderlyClosePersistsAcceptedFlowBeforeItsFirstDurableDeadline(boolean finalDrainFails) throws Exception {
        Resource iron = new Resource("shutdown-flow", 20, false);
        Grid grid = new Grid(710034, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setFlowTracked(true);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings()
            .setFlowFlushIntervalSeconds(60);
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        long startedAt = System.currentTimeMillis() - 6000;
        long stoppedAfter;
        try (StorageTrackingService service = new StorageTrackingService(store, 100)) {
            try (StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
                coordinator.tick(TestGridFixtures.ae(grid), startedAt);
                StorageCapture capture = grid.web$getStorageCapture();
                assertNotNull(capture);
                capture.recordFlow(iron, 7, 2);
                coordinator.tick(TestGridFixtures.ae(grid), startedAt + 5000);
                IAEKey finalResource = finalDrainFails ? new Resource("broken-description", 0, false) {

                    @Override
                    public @NotNull IAEKey web$copyIdentity() {
                        return this;
                    }

                    @Override
                    public @NotNull String web$getDisplayName() {
                        throw new IllegalStateException("Native description unavailable during shutdown");
                    }
                } : iron;
                capture.recordFlow(finalResource, 3, 1);
                capture.markFlowGap();
                stoppedAfter = System.currentTimeMillis();
            }
        }
        List<StorageHistoryPoint> points = store.read(key, iron.web$getKey(), 0, 100)
            .points();
        List<StorageHistoryPoint> flows = points.stream()
            .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
            .collect(Collectors.toList());
        assertEquals(1, flows.size());
        assertEquals(
            finalDrainFails ? 7 : 10,
            flows.get(0)
                .input());
        assertEquals(
            startedAt,
            flows.get(0)
                .flowFrom());
        assertEquals(
            finalDrainFails ? 2 : 3,
            flows.get(0)
                .output());
        assertTrue(
            flows.get(0)
                .observedAt() >= stoppedAfter);
        assertTrue(
            flows.get(0)
                .observedAt() <= System.currentTimeMillis());
        assertTrue(
            points.stream()
                .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.GAP));
    }

    @Test
    void recentFlowSamplesWithoutViewersBeforeOneMinuteDurableAggregation() throws Exception {
        Resource iron = new Resource("minute-flow", 20, false);
        Grid grid = new Grid(710033, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setFlowTracked(true);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings()
            .setFlowFlushIntervalSeconds(60);
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings,
                terminals)) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            capture.recordFlow(iron, 7, 2);
            coordinator.tick(TestGridFixtures.ae(grid), 6000);
            capture.recordFlow(iron, 9, 3);
            coordinator.tick(TestGridFixtures.ae(grid), 11000);
            assertTrue(
                store.read(key, iron.web$getKey(), 0, 100)
                    .points()
                    .isEmpty());

            coordinator.prepareForViewer(grid, 11000);
            Summary summary = awaitRecent(terminals, key, 11000);
            Amount amount = summary.resources()
                .get(iron.web$getKey());
            assertNotNull(amount);
            assertEquals("16", amount.input());
            assertEquals("2", amount.inputOperations());
            assertEquals("2", amount.outputOperations());
            assertEquals("5", amount.output());
            assertEquals(11000, summary.flowObservedAt());
            assertEquals(5000, summary.bucketMillis());

            capture.recordFlow(iron, 3, 1);
            coordinator.prepareForViewer(grid, 12000);
            coordinator.tick(TestGridFixtures.ae(grid), 16000);
            coordinator.tick(TestGridFixtures.ae(grid), 61000);
            service.close();
            List<StorageHistoryPoint> flows = store.read(key, iron.web$getKey(), 0, 100)
                .points()
                .stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
                .collect(Collectors.toList());
            assertEquals(1, flows.size());
            assertEquals(
                3,
                flows.get(0)
                    .inputOperations());
            assertEquals(
                3,
                flows.get(0)
                    .outputOperations());
            assertEquals(
                1000,
                flows.get(0)
                    .flowFrom());
            assertEquals(
                61000,
                flows.get(0)
                    .observedAt());
            assertEquals(
                19,
                flows.get(0)
                    .input());
            assertEquals(
                6,
                flows.get(0)
                    .output());
        }
    }

    @Test
    void recentPublicationDoesNotRunOnTheNativeCaptureThread() throws Exception {
        Resource iron = new Resource("async-recent", 10, false);
        Grid grid = new Grid(710032, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setStorageTracked(true);
        LiveHub hub = new LiveHub();
        TerminalRegistry terminals = new TerminalRegistry(hub);
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            synchronized (hub) {
                coordinator.prepareForViewer(grid, 1000);
                assertNotNull(terminals.snapshot(key));
                assertNull(terminals.recent(key), "Capture may enqueue recent work, but cannot execute it inline");
            }
            assertNotNull(awaitRecent(terminals, key, 1000));
        }
    }

    @Test
    void sampledStockKeepsAnHourWhileFlowExpiresAndReportsActualMeasurementTimes() throws Exception {
        Resource iron = new Resource("separate-windows", 10, false);
        Grid grid = new Grid(710031, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setStorageTracked(true);
        data.getSettings()
            .setFlowTracked(true);
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            coordinator.prepareForViewer(grid, 1000);
            Summary summary = awaitRecent(terminals, key, 1000);
            assertNotNull(summary);
            assertNull(
                summary.resources()
                    .get(iron.web$getKey())
                    .stockChange());
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            capture.recordFlow(iron, 17, 3);
            coordinator.prepareForViewer(grid, 6000);
            summary = awaitRecent(terminals, key, 6000);
            assertNotNull(summary);
            assertEquals(
                "17",
                summary.resources()
                    .get(iron.web$getKey())
                    .input());
            coordinator.prepareForViewer(grid, 131000);
            summary = awaitRecent(terminals, key, 131000);
            assertNotNull(summary);
            assertEquals(
                "0",
                summary.resources()
                    .get(iron.web$getKey())
                    .input());
            assertEquals(1000, summary.stockObservedAt());
            grid.rows.set(0, new Resource("separate-windows", 25, false));
            coordinator.prepareForViewer(grid, 301000);
            summary = awaitRecent(terminals, key, 301000);
            assertNotNull(summary);
            assertEquals(
                "15",
                summary.resources()
                    .get(iron.web$getKey())
                    .stockChange());
            assertEquals(1000, summary.stockSince());
            assertEquals(301000, summary.stockObservedAt());
            for (long now = 601000; now <= 3601000; now += 300000) coordinator.prepareForViewer(grid, now);
            summary = awaitRecent(terminals, key, 3601000);
            assertNotNull(summary);
            Long stockObservedAt = summary.stockObservedAt();
            Long stockSince = summary.stockSince();
            assertNotNull(stockObservedAt);
            assertNotNull(stockSince);
            assertEquals(3600000, stockObservedAt - stockSince);
            assertEquals(
                "15",
                summary.resources()
                    .get(iron.web$getKey())
                    .stockChange());
            coordinator.prepareForViewer(grid, 3901000);
            summary = awaitRecent(terminals, key, 3901000);
            assertNotNull(summary);
            assertEquals(
                "0",
                summary.resources()
                    .get(iron.web$getKey())
                    .stockChange());
            assertEquals(301000, summary.stockSince());
            assertEquals(3901000, summary.stockObservedAt(), "Unchanged samples still update measurement time");
            data.getSettings()
                .setStockSampleIntervalSeconds(7200);
            coordinator.prepareForViewer(grid, 4001000);
            coordinator.prepareForViewer(grid, 11201000);
            summary = awaitRecent(terminals, key, 11201000);
            assertNotNull(summary);
            assertEquals(summary.stockObservedAt(), summary.stockSince());
            assertNull(
                summary.resources()
                    .get(iron.web$getKey())
                    .stockChange(),
                "Samples farther apart than the window cannot describe an hourly change");
        }
    }

    @Test
    void stockCacheUsesOnlyConfiguredSamplesEvenWhenTerminalReadsMoreOften() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger measuredQuantity = new AtomicInteger(10);
        Resource iron = new Resource("sample-only", 10, false) {

            @Override
            public long web$amount() {
                reads.incrementAndGet();
                return measuredQuantity.get();
            }
        };
        Grid grid = new Grid(710030, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setStorageTracked(true);
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            int initialReads = reads.get();
            int initialStorageReads = grid.storageReads;
            measuredQuantity.set(20);
            capture.markChanged(iron);
            coordinator.tick(TestGridFixtures.ae(grid), 6000);
            assertEquals(initialReads, reads.get(), "An idle hot cache must not trigger native stock reads");
            assertEquals(initialStorageReads, grid.storageReads);
            coordinator.tick(TestGridFixtures.ae(grid), 301000);
            measuredQuantity.set(99);
            capture.markChanged(iron);
            coordinator.prepareForViewer(grid, 306000);
            StorageSnapshot snapshot = terminals.snapshot(key);
            assertNotNull(snapshot);
            Summary summary = awaitRecent(terminals, key, 306000);
            assertNotNull(summary);
            assertEquals(
                99,
                snapshot.rows()
                    .get(0)
                    .quantity());
            assertEquals(
                "10",
                summary.resources()
                    .get(iron.web$getKey())
                    .stockChange(),
                "A terminal bootstrap must preserve the two actual tracking samples");
            measuredQuantity.set(200);
            capture.markChanged(iron);
            coordinator.prepareForViewer(grid, 311000);
            summary = awaitRecent(terminals, key, 311000);
            assertNotNull(summary);
            assertEquals(
                "10",
                summary.resources()
                    .get(iron.web$getKey())
                    .stockChange());
        }
    }

    @Test
    void recentFlowRemainsKnownWhenDurableQueueRejectsAndStockTrackingIsDisabled() throws Exception {
        Resource iron = new Resource("recent-flow-only", 10, false);
        Grid grid = new Grid(710028, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData configuration = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(configuration);
        configuration.getSettings()
            .setFlowTracked(true);
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 1);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            coordinator.prepareForViewer(grid, 1000);
            Summary initial = awaitRecent(terminals, key, 1000);
            assertNotNull(initial);
            assertNull(initial.stockSince());
            assertEquals(
                "0",
                initial.resources()
                    .get(iron.web$getKey())
                    .input());
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            capture.recordFlow(iron, Long.MAX_VALUE, 2);
            coordinator.prepareForViewer(grid, 6000);
            capture.recordFlow(iron, Long.MAX_VALUE, 3);
            coordinator.prepareForViewer(grid, 11000);
            Summary summary = awaitRecent(terminals, key, 11000);
            assertNotNull(summary);
            assertTrue(summary.flowComplete(), "Durable queue loss must not poison measured live flow");
            Amount amount = summary.resources()
                .get(iron.web$getKey());
            assertEquals("18446744073709551614", amount.input());
            assertEquals("5", amount.output());
            assertNull(amount.stockChange());
            capture.markGap();
            coordinator.prepareForViewer(grid, 16000);
            summary = awaitRecent(terminals, key, 16000);
            assertNotNull(summary);
            assertFalse(summary.flowComplete());
            coordinator.prepareForViewer(grid, 141000);
            summary = awaitRecent(terminals, key, 141000);
            assertNotNull(summary);
            assertTrue(summary.flowComplete());
            assertEquals(
                "0",
                summary.resources()
                    .get(iron.web$getKey())
                    .input());
        }
    }

    @Test
    void recentStockDoesNotPretendToMeasureFlowAndRestartsWhenTrackingChanges() throws Exception {
        Resource iron = new Resource("recent-stock-only", 10, false);
        Grid grid = new Grid(710029, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData configuration = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(configuration);
        configuration.getSettings()
            .setStorageTracked(true);
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            coordinator.prepareForViewer(grid, 1000);
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            capture.recordFlow(iron, 50, 20);
            grid.rows.set(0, new Resource("recent-stock-only", 5, false));
            coordinator.prepareForViewer(grid, 301000);
            Summary summary = awaitRecent(terminals, key, 301000);
            assertNotNull(summary);
            assertNull(summary.flowSince());
            assertNull(
                summary.resources()
                    .get(iron.web$getKey())
                    .input());
            assertEquals(
                "-5",
                summary.resources()
                    .get(iron.web$getKey())
                    .stockChange());
            configuration.getSettings()
                .setStorageTracked(false);
            coordinator.prepareForViewer(grid, 306000);
            assertNull(terminals.recent(key));
        }
    }

    @Test
    void recentFlowAndStockAreIndependentAndExpireWithoutNewTransfers() throws Exception {
        Resource iron = new Resource("recent-iron", 10, false);
        Grid grid = new Grid(710027, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setStorageTracked(true);
        data.getSettings()
            .setFlowTracked(true);
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        ConfigSettings.ResourceTracking configuration = new ConfigSettings.ResourceTracking();
        CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings()
            .setFlowFlushIntervalSeconds(30);
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                configuration,
                terminals)) {
            coordinator.prepareForViewer(grid, 1000);
            StorageCapture capture = grid.web$getStorageCapture();
            assertNotNull(capture);
            capture.recordFlow(iron, 100, 100);
            coordinator.prepareForViewer(grid, 6000);
            Summary summary = awaitRecent(terminals, key, 6000);
            assertNotNull(summary);
            Amount amount = summary.resources()
                .get(iron.web$getKey());
            assertEquals("100", amount.input());
            assertEquals("100", amount.output());
            assertNull(amount.stockChange());
            assertEquals(1000, summary.flowSince());
            assertTrue(
                store.read(key, iron.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .noneMatch(point -> point.kind() == StorageHistoryPoint.Kind.FLOW));
            grid.rows.set(0, new Resource("recent-iron", 17, false));
            capture.markChanged(iron);
            coordinator.prepareForViewer(grid, 11000);
            summary = awaitRecent(terminals, key, 11000);
            assertNotNull(summary);
            assertNull(
                summary.resources()
                    .get(iron.web$getKey())
                    .stockChange());
            coordinator.tick(TestGridFixtures.ae(grid), 31000);
            coordinator.prepareForViewer(grid, 161000);
            summary = awaitRecent(terminals, key, 161000);
            assertNotNull(summary);
            amount = summary.resources()
                .get(iron.web$getKey());
            assertEquals("0", amount.input());
            assertEquals("0", amount.output());
            assertNull(amount.stockChange());
            service.close();
            assertEquals(
                100,
                store.read(key, iron.web$getKey(), 0, 100)
                    .points()
                    .stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
                    .mapToLong(StorageHistoryPoint::input)
                    .sum());
        }
    }

    @Test
    void pendingTerminalHandoffRenewsCaptureWithoutTransportTickOrHistory() throws Exception {
        Resource iron = new Resource("viewer-demand", 10, false);
        Grid grid = new Grid(710019, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
            coordinator.prepareForViewer(grid, 1000);
            AuthSession login = new AuthSession(WebPrincipal.admin(), Long.MAX_VALUE);
            assertNotNull(terminals.reserve(key, login, false, null, null, 1000));
            coordinator.tick(TestGridFixtures.ae(grid), 9000);
            grid.rows.set(0, new Resource("viewer-demand", 25, false));
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
        Resource iron = new Resource("reentrant-topology", 4, false) {

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
        Grid grid = new Grid(710012, iron);
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
        Grid grid = new Grid(710010);
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
            IAEKey resource = new Resource("transient-flow", 1, false);
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
        Resource iron = new Resource("removed-iron", 20, false);
        Grid grid = new Grid(710010, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        data.getSettings()
            .setFlowTracked(true);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings()
            .setFlowFlushIntervalSeconds(5);
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
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
        Resource iron = new Resource("baseline", 4, false);
        Grid grid = new Grid(710008, iron);
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

            @Override
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
        TerminalRegistry terminals = new TerminalRegistry(new LiveHub());
        try (StorageTrackingService service = new StorageTrackingService(blocked, 2);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                new ConfigSettings.ResourceTracking(),
                terminals)) {
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
                grid.rows.add(new Resource("baseline", 11, false));
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
                Summary summary = awaitRecent(terminals, key, 6000);
                assertEquals(
                    "7",
                    summary.resources()
                        .get(iron.web$getKey())
                        .input());
                assertTrue(summary.flowComplete(), "Blocked or rejected database writes do not block recent flow");
                assertFalse(
                    capture.requiresReconciliation(),
                    "History queue rejection must not force a terminal rescan");
                grid.rows.set(0, new Resource("baseline", 12, false));
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
        Resource iron = new Resource("icon-iron", 20, false) {

            public @NotNull IAEKey web$copyIdentity() {
                copies.incrementAndGet();
                return this;
            }

            public StableKey web$getBaseKey() {
                bases.incrementAndGet();
                return base;
            }
        };
        Grid grid = new Grid(710007, iron);
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
        Resource iron = new Resource("iron", 20, false);
        Grid grid = new Grid(710006, iron);
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
        CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings()
            .setFlowFlushIntervalSeconds(5);
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
            grid.rows.add(new Resource("iron", 27, false));
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
        CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings()
            .setFlowFlushIntervalSeconds(5);
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100);
            StorageTrackingCoordinator coordinator = new StorageTrackingCoordinator(
                service,
                CoreEngine.GRID_IDENTITIES,
                settings)) {
            coordinator.tick(TestGridFixtures.ae(grid), 1000);
            assertNotNull(capture[0]);
            Resource iron = new Resource("iron", 20, false);
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
        Resource broken = new Resource("broken", 1, false) {

            @Override
            public @NotNull IAEKey web$copyIdentity() {
                attempts.incrementAndGet();
                if (failing.get()) throw cause;
                return super.web$copyIdentity();
            }
        };
        Grid grid = new Grid(710003, broken);
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
            StorageSnapshot recovered = coordinator.observeForViewer(grid, 41000);
            assertNotNull(recovered);
            assertFalse(
                recovered.rows()
                    .isEmpty());
        }
    }

    @Test
    void failedIdleObservationRetriesAtIdleCadenceInsteadOfRescanningEveryTick() {
        AtomicInteger attempts = new AtomicInteger();
        Resource unsupported = new Resource("unsupported", 1, false) {

            @Override
            public @NotNull IAEKey web$copyIdentity() {
                attempts.incrementAndGet();
                throw new IllegalStateException("Broken native capture");
            }
        };
        Grid grid = new Grid(710002, unsupported);
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
        Resource iron = new Resource("iron", 20, false);
        Grid grid = new Grid(710001, iron);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData persistentData = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(persistentData);
        persistentData.getSettings()
            .setStorageTracked(true);
        persistentData.getSettings()
            .setFlowTracked(true);
        ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
        CoreEngine.GRID_IDENTITIES.getPersistentData(key)
            .getSettings()
            .setFlowFlushIntervalSeconds(5);
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

    private static final class FaultGrid extends TestGridFixtures.TestGrid implements IAEStorageGrid {

        private final Grid contents;
        private StorageCapture capture;
        private StorageCapture hookCapture;
        private String attachmentFailure;
        private String detachFailure;
        private int storageAccesses;
        private boolean failEligibility;
        private int eligibilityReads;

        private FaultGrid(long id, Resource... resources) {
            super(id, false, AEControllerState.CONTROLLER_ONLINE);
            contents = new Grid(id, resources);
        }

        @Override
        public IAEPathingGrid web$getPathingGrid() {
            eligibilityReads++;
            if (failEligibility) throw new IllegalStateException("Native pathing unavailable");
            return super.web$getPathingGrid();
        }

        @Override
        public IAEStorageGrid web$getStorageGrid() {
            storageAccesses++;
            if ("access".equals(attachmentFailure) || "access".equals(detachFailure))
                throw new IllegalStateException("Native storage unavailable");
            return this;
        }

        @Override
        public IAECraftingGrid web$getCraftingGrid() {
            return contents;
        }

        @Override
        public IStackList web$getStorageList() {
            return contents.web$getStorageList();
        }

        @Override
        public IAEMeInventoryItem web$getInventory() {
            return contents.web$getInventory();
        }

        @Override
        public StorageCapture web$getStorageCapture() {
            if ("getter".equals(detachFailure)) throw new IllegalStateException("Native capture owner unavailable");
            return capture;
        }

        @Override
        public void web$setStorageCapture(StorageCapture value) {
            if (value != null && "before".equals(attachmentFailure))
                throw new IllegalStateException("Native capture installation failed");
            if (value == null && "before".equals(detachFailure))
                throw new IllegalStateException("Native capture removal failed");
            capture = value;
            if (value != null && "after".equals(attachmentFailure))
                throw new IllegalStateException("Native capture installation partially failed");
            if (value == null && "after".equals(detachFailure))
                throw new IllegalStateException("Native capture removal partially failed");
            hookCapture = value;
        }
    }

    private static @NotNull Summary awaitRecent(TerminalRegistry terminals, StableKey key, long observedAt)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Summary summary = terminals.recent(key);
            if (summary != null && summary.observedAt() >= observedAt) return summary;
            Thread.sleep(1);
        }
        throw new AssertionError("Recent tracking did not publish observation " + observedAt);
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
