package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.ae2request.async.GetTrackingHistory;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.Job;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class AE2JobTrackerLifecycleTest extends GridTestScope {

    private StableKey gridKey;
    private final TestGridFixtures.TestGrid grid = TestGridFixtures.grid(900_101L);

    @BeforeEach
    void setUp() {
        AE2JobTracker.clearActiveJobs();
        TestGridFixtures.track(grid);
        gridKey = CoreEngine.GRID_IDENTITIES.getKey(grid);
        GridData.getOrCreate(gridKey).trackingInfo.clearHistory();
    }

    @AfterEach
    void tearDown() {
        AE2JobTracker.clearActiveJobs();
        GridData data = GridData.find(gridKey);
        if (data != null) data.trackingInfo.clearHistory();
    }

    @Test
    void controllerAdditionDuringCraftingPublishesTheMergedOutputImmediately() {
        Set<DimensionalCoords> positions = new LinkedHashSet<>();
        positions.add(new DimensionalCoords("world", 0, 0, 0));
        TestGridFixtures.TestGrid changing = new TestGridFixtures.TestGrid(
            900_104L,
            false,
            AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                return positions;
            }
        };
        TestGridFixtures.track(changing);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(changing);
        assertNotNull(key);
        EqualCpu cpu = new EqualCpu();
        cpu.currentGrid = changing;
        AE2JobTracker.addJob(cpu, changing, false);
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        positions.add(new DimensionalCoords("world", 1, 0, 0));
        CoreEngine.GRID_IDENTITIES.controllerValidated(changing);
        cpu.output = new OutputSnapshotTest.Stack(new OutputSnapshotTest.Resource(), 9);
        AE2JobTracker.addJob(cpu, changing, true);
        AE2JobTracker.completeCrafting(changing, cpu);
        assertSame(info, GridData.getOrCreate(key).trackingInfo.trackingInfos.get(1));
        IAE previous = AE2Controller.AE2Interface;
        try {
            AE2Controller.AE2Interface = TestGridFixtures.ae(changing);
            CoreEngine.onServerTick();
            assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(changing));
            assertSame(info, GridData.getOrCreate(key).trackingInfo.trackingInfos.get(1));
            assertEquals(9, info.finalOutput.quantity);
        } finally {
            AE2Controller.AE2Interface = previous;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void conflictCompletionWaitsForAValidControllerCallbackAndPublishesOnce(boolean bindingAlreadyKnown)
        throws Exception {
        EqualCpu cpu = new EqualCpu();
        cpu.currentGrid = grid;
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        assertNotNull(info);
        if (!bindingAlreadyKnown) CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        grid.controllerState(AEControllerState.CONTROLLER_CONFLICT);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);

        AE2JobTracker.completeCrafting(grid, cpu);
        assertTrue(info.isDone);
        long completedAt = info.timeDone;
        AE2JobTracker.resolveDeferredJobs();
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.isEmpty());

        grid.controllerState(AEControllerState.CONTROLLER_ONLINE);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        AE2JobTracker.resolveDeferredJobs();
        AE2JobTracker.resolveDeferredJobs();
        assertEquals(gridKey, CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertEquals(completedAt, info.timeDone);
        assertSame(info, GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.get(1));
        assertEquals(1, GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.size());
    }

    @Test
    void cachedLifecycleAndHotUpdatesDoNotEnumerateControllers() {
        AtomicBoolean mayReadControllers = new AtomicBoolean(true);
        TestGridFixtures.TestGrid stable = new TestGridFixtures.TestGrid(
            900_103L,
            false,
            AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                if (!mayReadControllers.get())
                    throw new AssertionError("Crafting callbacks must use the prepared binding");
                return super.web$getControllers();
            }
        };
        TestGridFixtures.track(stable);
        StableKey stableKey = CoreEngine.GRID_IDENTITIES.getKey(stable);
        assertNotNull(stableKey);
        mayReadControllers.set(false);
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, stable, false);
        Resource resource = new Resource(1, 0);
        push(cpu, "provider", new DimensionalCoords("world", 0, 0, 0), resource);
        update(cpu, resource, 1);
        update(cpu, resource, 0);
        cpu.output = new OutputSnapshotTest.Stack(new OutputSnapshotTest.Resource(), 9);
        AE2JobTracker.addJob(cpu, stable, true);
        AE2JobTracker.completeCrafting(stable, cpu);

        assertEquals(9, GridData.getOrCreate(stableKey).trackingInfo.trackingInfos.get(1).finalOutput.quantity);
    }

    @Test
    void unresolvedJobsAreDiscardedWhenTheirCurrentGridResolvesUntracked() throws Exception {
        CoreEngine.GRID_IDENTITIES.setTracked(gridKey, false);
        EqualCpu known = new EqualCpu();
        AE2JobTracker.addJob(known, grid, false);
        assertNull(AE2JobTracker.findActiveJob(known));
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        EqualCpu active = new EqualCpu();
        EqualCpu completed = new EqualCpu();
        AE2JobTracker.addJob(active, grid, false);
        AE2JobTracker.addJob(completed, grid, false);
        assertNotNull(AE2JobTracker.findActiveJob(active));
        AE2JobTracker.completeCrafting(grid, completed);

        AE2JobTracker.resolveDeferredJobs();
        assertNotNull(AE2JobTracker.findActiveJob(active));
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        AE2JobTracker.resolveDeferredJobs();
        assertNull(AE2JobTracker.findActiveJob(active));
        CoreEngine.GRID_IDENTITIES.setTracked(gridKey, true);
        AE2JobTracker.resolveDeferredJobs();
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.isEmpty());
    }

    @Test
    void stopCleanupDiscardsProvisionalAndCompletedPendingJobs() throws Exception {
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.completeCrafting(grid, cpu);
        AE2JobTracker.addJob(cpu, grid, false);
        assertNotNull(AE2JobTracker.findActiveJob(cpu));

        AE2JobTracker.clearActiveJobs();
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        AE2JobTracker.resolveDeferredJobs();

        assertNull(AE2JobTracker.findActiveJob(cpu));
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.isEmpty());
    }

    @Test
    void startsAndCompletionsBeforeFirstValidationKeepBothJobsFromTheSameCpu() throws Exception {
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.JobTrackingInfo first = AE2JobTracker.findActiveJob(cpu);
        assertNotNull(first);
        AE2JobTracker.completeCrafting(grid, cpu);
        cpu.output = new OutputSnapshotTest.Stack(new OutputSnapshotTest.Resource(), 9);
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.JobTrackingInfo second = AE2JobTracker.findActiveJob(cpu);
        assertNotNull(second);
        AE2JobTracker.completeCrafting(grid, cpu);
        AE2JobTracker.resolveDeferredJobs();
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.isEmpty());

        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        AE2JobTracker.resolveDeferredJobs();

        assertEquals(2, GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.size());
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.containsValue(first));
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.containsValue(second));
        assertEquals(5, first.finalOutput.quantity);
        assertEquals(9, second.finalOutput.quantity);
    }

    @Test
    void completionFinalizesBeforeControllerValidationThenPublishesOnceToCurrentGrid() throws Exception {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);

        AE2JobTracker.completeCrafting(grid, cpu);

        assertTrue(info.isDone);
        long completedAt = info.timeDone;
        assertNull(AE2JobTracker.findActiveJob(cpu));
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.isEmpty());
        TestGridFixtures.TestGrid destination = TestGridFixtures.grid(900_102L);
        TestGridFixtures.track(destination);
        cpu.currentGrid = destination;
        StableKey destinationKey = CoreEngine.GRID_IDENTITIES.getKey(destination);
        assertNotNull(destinationKey);
        AE2JobTracker.resolveDeferredJobs();
        AE2JobTracker.resolveDeferredJobs();

        assertEquals(completedAt, info.timeDone);
        assertSame(info, GridData.getOrCreate(destinationKey).trackingInfo.trackingInfos.get(1));
        assertEquals(1, GridData.getOrCreate(destinationKey).trackingInfo.trackingInfos.size());
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.isEmpty());
    }

    @Test
    void existingMergeCapturesOutputWhileItsGridBindingIsUnavailable() throws Exception {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        cpu.output = new OutputSnapshotTest.Stack(new OutputSnapshotTest.Resource(), 9);

        AE2JobTracker.addJob(cpu, grid, true);

        assertSame(info, AE2JobTracker.findActiveJob(cpu));
        assertEquals(9, info.finalOutput.quantity);
    }

    @Test
    void mergeRetiresLosingPlansAndHistoryWithoutDiscardingTheWinningPlan() throws Exception {
        TestGridFixtures.TestGrid other = TestGridFixtures.grid(900_102L);
        GridAccess.list(TestGridFixtures.ae(grid, other));
        StableKey losingKey = CoreEngine.GRID_IDENTITIES.getKey(other);
        assertNotNull(losingKey);
        CoreEngine.GRID_IDENTITIES.setTracked(losingKey, true);
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, other, false);
        AE2JobTracker.completeCrafting(other, cpu);
        CoreEngine.GRID_IDENTITIES.setTracked(losingKey, false);
        CompletableFuture<IAECraftingJob> losingPlan = new CompletableFuture<>();
        int losingId = GridData.getOrCreate(losingKey)
            .addJob(losingPlan);
        CompletableFuture<IAECraftingJob> winningPlan = new CompletableFuture<>();
        int winningId = GridData.getOrCreate(gridKey)
            .addJob(winningPlan);
        TestGridFixtures.TestGrid merged = new TestGridFixtures.TestGrid(
            gridKey,
            false,
            AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                Set<DimensionalCoords> result = new LinkedHashSet<>(grid.web$getControllers());
                result.addAll(other.web$getControllers());
                return result;
            }
        };

        CoreEngine.GRID_IDENTITIES.controllerValidated(merged);
        GridAccess.list(TestGridFixtures.ae(merged));
        assertEquals(gridKey, CoreEngine.GRID_IDENTITIES.getKey(merged));
        assertTrue(losingPlan.isCancelled());
        assertSame(
            winningPlan,
            GridData.getOrCreate(gridKey)
                .getJob(winningId));

        TestGridFixtures.TestAE split = TestGridFixtures.ae(grid, other);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        CoreEngine.GRID_IDENTITIES.controllerValidated(other);
        GridAccess.list(split);
        StableKey splitKey = CoreEngine.GRID_IDENTITIES.getKey(other);
        assertNotNull(splitKey);
        assertNotEquals(losingKey, splitKey);
        assertEmptyHistory(TestGridFixtures.OWNER_ID, splitKey);
        Job request = new Job();
        assertTrue(
            request.init(TestGridFixtures.context(TestGridFixtures.OWNER_ID, "grid=" + splitKey + "&id=" + losingId)));
        request.runOnServerThread(split);
        assertEquals(
            "INVALID_ID",
            JsonParser.parseString(request.getJSON())
                .getAsJsonObject()
                .get("status")
                .getAsString());
    }

    @Test
    void rebuiltControllerDoesNotExposePreviousHistoryOrPlansToItsNewOwner() {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.completeCrafting(grid, cpu);
        CompletableFuture<IAECraftingJob> plan = new CompletableFuture<>();
        int planId = GridData.getOrCreate(gridKey)
            .addJob(plan);

        CoreEngine.GRID_IDENTITIES.controllerRemoved(grid.position());
        TestGridFixtures.TestGrid replacement = TestGridFixtures.grid(900_101L, 42);
        TestGridFixtures.TestAE ae = TestGridFixtures.ae(replacement);

        StableKey replacementKey = CoreEngine.GRID_IDENTITIES.getKey(replacement);
        assertNotNull(replacementKey);
        assertNotEquals(gridKey, replacementKey);
        assertEmptyHistory(42, replacementKey);
        assertTrue(plan.isCancelled());
        Job request = new Job();
        assertTrue(request.init(TestGridFixtures.context(42, "grid=" + replacementKey + "&id=" + planId)));
        request.runOnServerThread(ae);
        assertEquals(
            "INVALID_ID",
            JsonParser.parseString(request.getJSON())
                .getAsJsonObject()
                .get("status")
                .getAsString());
    }

    private static void assertEmptyHistory(int playerId, StableKey key) {
        GetTrackingHistory request = new GetTrackingHistory();
        request.handle(TestGridFixtures.context(playerId, "grid=" + key));
        var response = JsonParser.parseString(request.getJSON())
            .getAsJsonObject();
        assertEquals(
            "OK",
            response.get("status")
                .getAsString());
        assertEquals(
            0,
            response.getAsJsonArray("data")
                .size());
    }

    @Test
    void unrelatedAndUnknownControllerRemovalPreservesActiveCompletion() throws Exception {
        TestGridFixtures.TestGrid other = TestGridFixtures.grid(900_102L);
        GridAccess.list(TestGridFixtures.ae(grid, other));
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);

        CoreEngine.GRID_IDENTITIES.controllerRemoved(other.position());
        CoreEngine.GRID_IDENTITIES.controllerRemoved(new DimensionalCoords("unknown", 1, 2, 3));
        AE2JobTracker.completeCrafting(grid, cpu);

        assertSame(info, GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.get(1));
        assertNotNull(info);
        assertTrue(info.isDone);
    }

    @Test
    void distinctCpuObjectsKeepDistinctTrackingEntriesEvenWhenTheyCompareEqual() {
        EqualCpu first = new EqualCpu();
        EqualCpu second = new EqualCpu();

        AE2JobTracker.addJob(first, grid, false);
        AE2JobTracker.addJob(second, grid, false);

        AE2JobTracker.JobTrackingInfo firstInfo = AE2JobTracker.findActiveJob(first);
        AE2JobTracker.JobTrackingInfo secondInfo = AE2JobTracker.findActiveJob(second);
        assertNotNull(firstInfo);
        assertNotNull(secondInfo);
        assertNotSame(firstInfo, secondInfo);
    }

    @Test
    void serverStopCleanupDropsEveryActiveCpuTrackingEntry() {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        assertNotNull(AE2JobTracker.findActiveJob(cpu));

        AE2JobTracker.clearActiveJobs();

        assertNull(AE2JobTracker.findActiveJob(cpu));
    }

    @Test
    void missingFinalOutputDoesNotStartTrackingOrProduceHistory() {
        EqualCpu cpu = new EqualCpu();
        cpu.output = null;
        AE2JobTracker.addJob(cpu, grid, false);

        assertNull(AE2JobTracker.findActiveJob(cpu));
        update(cpu, new Resource(1, 0), 10);
        AE2JobTracker.completeCrafting(grid, cpu);
        AE2JobTracker.cancelCrafting(grid, cpu);
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.isEmpty());
    }

    @Test
    void missingFinalOutputOnMergeDiscardsTrackingWithoutPublishingPartialHistory() {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        update(cpu, new Resource(1, 0), 10);
        assertNotNull(AE2JobTracker.findActiveJob(cpu));

        cpu.output = null;
        AE2JobTracker.addJob(cpu, grid, true);

        assertNull(AE2JobTracker.findActiveJob(cpu));
        cpu.output = new OutputSnapshotTest.Stack(new OutputSnapshotTest.Resource(), 9);
        AE2JobTracker.addJob(cpu, grid, true);
        assertNull(AE2JobTracker.findActiveJob(cpu));
        update(cpu, new Resource(1, 0), 0);
        AE2JobTracker.completeCrafting(grid, cpu);
        assertTrue(GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.isEmpty());
    }

    @Test
    void completionKeepsKnownOutputWhenTheNativeCpuHasAlreadyClearedItsStack() {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        cpu.output = null;

        AE2JobTracker.completeCrafting(grid, cpu);

        assertNull(AE2JobTracker.findActiveJob(cpu));
        assertSame(info, GridData.getOrCreate(gridKey).trackingInfo.trackingInfos.get(1));
        assertTrue(info.isDone);
        assertEquals(5, info.finalOutput.quantity);
        assertEquals("example:resource:7", info.finalOutput.itemid);
    }

    @Test
    void deliveriesKeepTaggedVariantsSeparateAcrossEquivalentKeysAndWaitingCycles() {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        Resource first = new Resource(1, 0);
        Resource second = new Resource(1, 1);

        update(cpu, first, 10);
        update(cpu, second, 20);
        update(cpu, new Resource(1, 0), 6);
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        assertEquals(4L, info.craftedTotal.get(first));
        assertEquals(20L, info.waitingFor.get(second));

        update(cpu, new Resource(1, 0), 0);
        assertEquals(10L, info.craftedTotal.get(first));
        assertTrue(info.startedWaitingFor.containsKey(second));
        update(cpu, new Resource(1, 0), 3);
        update(cpu, new Resource(1, 0), 0);
        update(cpu, new Resource(1, 1), 0);

        assertEquals(13L, info.craftedTotal.get(first));
        assertEquals(20L, info.craftedTotal.get(second));
        assertEquals(
            2,
            info.itemShare.get(first)
                .size());
        assertEquals(
            1,
            info.itemShare.get(second)
                .size());
        assertTrue(info.startedWaitingFor.isEmpty());
        assertTrue(info.waitingFor.isEmpty());
        assertEquals(
            info.timeSpentOn.get(first)
                .longValue(),
            info.getTimeSpentOn(new Resource(1, 0)));
    }

    @Test
    void providersShareNamesAndLocationsButFinishOnlyAfterAllTheirOutputsArrive() {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        Resource first = new Resource(1, 0);
        Resource second = new Resource(2, 0);
        DimensionalCoords firstLocation = new DimensionalCoords(0, 1, 2, 3);
        DimensionalCoords secondLocation = new DimensionalCoords(0, 4, 5, 6);
        // Aa and BB also exercise distinct provider names with the same String hash.
        push(cpu, "Aa", firstLocation, first);
        // Equal names must group even when they are different String instances.
        // noinspection StringOperationCanBeSimplified
        push(cpu, new String("Aa"), secondLocation, second);
        push(cpu, "BB", firstLocation, first);
        push(cpu, "Aa", firstLocation, first);
        update(cpu, first, 10);
        update(cpu, second, 20);

        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        assertEquals(2, info.interfaceStarted.size());
        AE2JobTracker.AEInterface grouped = info.interfaceStarted.keySet()
            .stream()
            .filter(provider -> provider.name.equals("Aa"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected the grouped provider named Aa"));
        assertEquals(2, grouped.location.size());
        assertTrue(grouped.location.contains(firstLocation));
        assertTrue(grouped.location.contains(secondLocation));

        update(cpu, new Resource(1, 0), 0);
        assertEquals(1, info.interfaceStarted.size());
        assertTrue(info.interfaceStarted.containsKey(grouped));
        update(cpu, new Resource(2, 0), 0);
        assertTrue(info.interfaceStarted.isEmpty());
        assertEquals(2, info.interfaceShare.size());
        assertEquals(
            1,
            info.interfaceShare.get(grouped)
                .size());

        push(cpu, "Aa", firstLocation, first);
        update(cpu, first, 5);
        update(cpu, new Resource(1, 0), 0);
        assertEquals(
            2,
            info.interfaceShare.get(grouped)
                .size());
        assertEquals(2, grouped.location.size());
    }

    private static void push(EqualCpu cpu, String name, DimensionalCoords location, Resource resource) {
        IPatternProviderViewable provider = new IPatternProviderViewable() {

            @Override
            public String web$getName() {
                return name;
            }

            @Override
            public DimensionalCoords web$getLocation() {
                return location;
            }
        };
        IAEGenericStack output = new IAEGenericStack() {

            @Override
            public @NotNull IAEKey web$what() {
                return resource;
            }

            @Override
            public long web$amount() {
                return 1;
            }

        };
        IAECraftingPatternDetails pattern = () -> new IAEGenericStack[] { output };
        AE2JobTracker.pushedPattern(cpu, provider, pattern);
    }

    private static void update(EqualCpu cpu, Resource resource, long remaining) {
        cpu.waiting.put(resource, remaining);
        AE2JobTracker.updateCraftingStatus(cpu, resource);
    }

    @Desugar
    private record Resource(int id, int variant) implements IAEKey {

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public @NotNull StableKey web$getKey() {
            throw new AssertionError("Tracking must not encode stable resource IDs");
        }

        @Override
        public @NotNull IAEKey web$copyIdentity() {
            return this;
        }

        @Override
        public @NotNull String web$getItemID() {
            return "test:resource";
        }

        @Override
        public @NotNull String web$getDisplayName() {
            return "Resource";
        }

        @Override
        public boolean web$isCraftable(IAEGrid grid) {
            return true;
        }
    }

    private static final class EqualCpu implements ICraftingCPUCluster {

        private IAEGrid currentGrid;

        @Override
        public IAEGrid web$getGrid() {
            return currentGrid;
        }

        public @NotNull StableKey web$getKey() {
            return StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        }

        private IAEGenericStack output = new OutputSnapshotTest.Stack(new OutputSnapshotTest.Resource(), 5);
        private final HashMap<IAEKey, Long> waiting = new HashMap<>();
        private final IStackList waitingList = new IStackList() {

            @Override
            public long web$getAmount(IAEKey key) {
                return waiting.getOrDefault(key, 0L);
            }

            @Override
            public Iterable<IAEGenericStack> web$stacks() {
                return Collections.emptyList();
            }
        };

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualCpu;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public String web$getName() {
            return "cpu";
        }

        @Override
        public long web$getAvailableStorage() {
            return 0;
        }

        @Override
        public long web$getUsedStorage() {
            return 0;
        }

        @Override
        public long web$getCoProcessors() {
            return 0;
        }

        @Override
        public boolean web$isBusy() {
            return true;
        }

        @Override
        public void web$cancel() {}

        @Override
        public IAEGenericStack web$getFinalOutput() {
            return output;
        }

        @Override
        public long web$getActiveItems(IAEKey key) {
            return 0;
        }

        @Override
        public long web$getPendingItems(IAEKey key) {
            return 0;
        }

        @Override
        public long web$getStorageItems(IAEKey key) {
            return 0;
        }

        @Override
        public void web$getAllItems(IStackList list) {}

        @Override
        public IStackList web$getWaitingFor() {
            return waitingList;
        }
    }
}
