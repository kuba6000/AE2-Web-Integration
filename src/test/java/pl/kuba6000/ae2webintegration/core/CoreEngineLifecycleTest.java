package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

@SuppressWarnings({ "UnstableApiUsage", "PMD.AvoidMagicNumbers" })
class CoreEngineLifecycleTest extends GridTestScope {

    @Test
    void serverTickWaitsForControllerValidationBeforeRestoringSavedBindings() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(900_202L);
        TestGridFixtures.track(grid);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
        IAE previous = AE2Controller.AE2Interface;
        try {
            AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
            CoreEngine.onServerTick();
            assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
            CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
            assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(grid));
            assertTrue(TestGridFixtures.isTracked(CoreEngine.GRID_IDENTITIES, key));
        } finally {
            AE2Controller.AE2Interface = previous;
        }
    }

    @Test
    void serverStoppedClearsWorldRuntimeStateButPreservesProcessState() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(900_201L);
        TestGridFixtures.track(grid);
        StableKey gridKey = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(gridKey);
        GridData gridData = GridData.getOrCreate(gridKey);
        CompletableFuture<IAECraftingJob> pendingPlan = new CompletableFuture<>();
        int planId = gridData.addJob(pendingPlan);
        ICraftingCPUCluster cpu = new TestCpu();
        AE2JobTracker.addJob(cpu, grid, false);
        gridData.trackingInfo.trackingInfos.put(1, AE2JobTracker.findActiveJob(cpu));
        AE2Controller.awaitingRegistration.put(UUID.randomUUID(), Pair.of("token", "password"));
        pl.kuba6000.ae2webintegration.core.identity.StableKey itemKey = AE2Controller.itemIdentities.remember(
            grid,
            cpu.web$getFinalOutput()
                .web$what());

        IAE processInterface = TestGridFixtures.ae(grid);
        AE2Controller.AE2Interface = processInterface;
        IServerPlatform processPlatform = new IServerPlatform() {

            @Override
            public UUID getOnlinePlayerUUID(String username) {
                return null;
            }

            @Override
            public File getConfigDirectory() {
                return null;
            }

            @Override
            public File getWorldDirectory() {
                return gridSave;
            }
        };
        AE2Controller.serverPlatform = processPlatform;
        CoreEngine.onServerStopped();
        assertDoesNotThrow(CoreEngine::onServerStopped, "world teardown must be idempotent");

        assertSame(processInterface, AE2Controller.AE2Interface);
        assertSame(processPlatform, AE2Controller.serverPlatform);
        assertTrue(
            TestGridFixtures.isTracked(
                new GridIdentityRegistry(new File(gridSave, "ae2webintegration/grid-identities.json")),
                gridKey),
            "settings remain in the stopped save");
        assertFalse(
            TestGridFixtures.isTracked(CoreEngine.GRID_IDENTITIES, gridKey),
            "the stopped world must not leak tracking into another world");
        assertTrue(AE2Controller.awaitingRegistration.isEmpty());
        assertNull(AE2Controller.itemIdentities.resolve(itemKey));
        assertNull(CoreEngine.GRID_IDENTITIES.getGrid(gridKey));
        assertNull(AE2JobTracker.findActiveJob(cpu));
        assertTrue(gridData.trackingInfo.trackingInfos.isEmpty());
        assertNull(gridData.getJob(planId));
        assertTrue(pendingPlan.isCancelled());
        assertEquals(1, gridData.addJob(new CompletableFuture<>()), "the next world gets fresh plan ids");
    }

    private static final class TestStack implements IAEGenericStack, IAEKey {

        @Override
        public @NotNull StableKey web$getKey() {
            return StableKey.create(sink -> sink.putBytes(new byte[] { 7 }));
        }

        @Override
        public @NotNull IAEKey web$copyIdentity() {
            return this;
        }

        @Override
        public @NotNull String web$getItemID() {
            return "test:output";
        }

        @Override
        public @NotNull String web$getDisplayName() {
            return "Output";
        }

        @Override
        public boolean web$isCraftable(pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid grid) {
            return false;
        }

        @Override
        public @NotNull IAEKey web$what() {
            return this;
        }

        @Override
        public long web$amount() {
            return 1;
        }

    }

    private static final class TestCpu implements ICraftingCPUCluster {

        @Override
        public pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid web$getGrid() {
            return null;
        }

        public @NotNull StableKey web$getKey() {
            return StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        }

        private final IAEGenericStack output = new TestStack();

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
            return null;
        }
    }
}
