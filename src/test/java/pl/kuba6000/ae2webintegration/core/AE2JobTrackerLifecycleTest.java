package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

class AE2JobTrackerLifecycleTest {

    private static final long GRID_KEY = 900_101L;
    private final TestGridFixtures.TestGrid grid = TestGridFixtures.grid(GRID_KEY);

    @BeforeEach
    void setUp() {
        AE2JobTracker.clearActiveJobs();
        GridData.getOrCreate(GRID_KEY).isTracked = true;
    }

    @Test
    void distinctCpuObjectsKeepDistinctTrackingEntriesEvenWhenTheyCompareEqual() {
        EqualCpu first = new EqualCpu();
        EqualCpu second = new EqualCpu();

        AE2JobTracker.addJob(first, null, grid, false);
        AE2JobTracker.addJob(second, null, grid, false);

        AE2JobTracker.JobTrackingInfo firstInfo = AE2JobTracker.findActiveJob(first);
        AE2JobTracker.JobTrackingInfo secondInfo = AE2JobTracker.findActiveJob(second);
        assertNotNull(firstInfo);
        assertNotNull(secondInfo);
        assertNotSame(firstInfo, secondInfo);
    }

    @Test
    void serverStopCleanupDropsEveryActiveCpuTrackingEntry() {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, null, grid, false);
        assertNotNull(AE2JobTracker.findActiveJob(cpu));

        AE2JobTracker.clearActiveJobs();

        assertNull(AE2JobTracker.findActiveJob(cpu));
    }

    private static final class EqualCpu implements ICraftingCPUCluster {

        private final IAEGenericStack output = new IAEGenericStack() {

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
