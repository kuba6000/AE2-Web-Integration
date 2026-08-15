package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

class CoreEngineLifecycleTest {

    private static final long GRID_KEY = 900_201L;

    @Test
    void serverStoppedClearsWorldRuntimeStateButPreservesProcessState() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(GRID_KEY);
        GridData gridData = GridData.getOrCreate(GRID_KEY);
        gridData.isTracked = true;
        CompletableFuture<IAECraftingJob> pendingPlan = new CompletableFuture<>();
        int planId = gridData.addJob(pendingPlan);
        gridData.trackingInfo.trackingInfos.put(1, new AE2JobTracker.JobTrackingInfo());

        ICraftingCPUCluster cpu = new TestCpu();
        AE2JobTracker.addJob(cpu, null, grid, false);
        GridAccessSessions.put(42, new GridAccess(Collections.singleton(GRID_KEY), 0L));
        AE2Controller.awaitingRegistration.put(UUID.randomUUID(), Pair.of("token", "password"));
        AE2Controller.hashcodeToStack.put(1, new TestStack());

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
        };
        AE2Controller.serverPlatform = processPlatform;
        CoreEngine.onServerStopped();
        assertDoesNotThrow(CoreEngine::onServerStopped, "world teardown must be idempotent");

        assertSame(processInterface, AE2Controller.AE2Interface);
        assertSame(processPlatform, AE2Controller.serverPlatform);
        assertTrue(gridData.isTracked, "persisted grid settings survive a world switch");
        assertTrue(AE2Controller.awaitingRegistration.isEmpty());
        assertTrue(AE2Controller.hashcodeToStack.isEmpty());
        assertNull(GridAccessSessions.get(42));
        assertNull(AE2JobTracker.findActiveJob(cpu));
        assertTrue(gridData.trackingInfo.trackingInfos.isEmpty());
        assertNull(gridData.getJob(planId));
        assertTrue(pendingPlan.isCancelled());
        assertEquals(1, gridData.addJob(new CompletableFuture<>()), "the next world gets fresh plan ids");
    }

    private static final class TestStack implements IAEGenericStack {

        @Override
        public IAEKey web$what() {
            return null;
        }

        @Override
        public long web$amount() {
            return 1;
        }

        @Override
        public IAEGenericStack web$copy() {
            return this;
        }
    }

    private static final class TestCpu implements ICraftingCPUCluster {

        private final IAEGenericStack output = new TestStack();

        @Override
        public void web$setInternalID(int id) {}

        @Override
        public boolean web$hasCustomName() {
            return false;
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
            return null;
        }
    }
}
