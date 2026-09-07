package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
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

    @Test
    void deliveriesKeepTaggedVariantsSeparateAcrossEquivalentKeysAndWaitingCycles() {
        EqualCpu cpu = new EqualCpu();
        AE2JobTracker.addJob(cpu, null, grid, false);
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
        AE2JobTracker.addJob(cpu, null, grid, false);
        Resource first = new Resource(1, 0);
        Resource second = new Resource(2, 0);
        DimensionalCoords firstLocation = new DimensionalCoords(0, 1, 2, 3);
        DimensionalCoords secondLocation = new DimensionalCoords(0, 4, 5, 6);
        // Aa and BB also exercise distinct provider names with the same String hash.
        push(cpu, "Aa", firstLocation, first);
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
            .get();
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
            public IAEKey web$what() {
                return resource;
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
        IAECraftingPatternDetails pattern = () -> new IAEGenericStack[] { output };
        AE2JobTracker.pushedPattern(cpu, provider, pattern);
    }

    private static void update(EqualCpu cpu, Resource resource, long remaining) {
        cpu.waiting.put(resource, remaining);
        AE2JobTracker.updateCraftingStatus(cpu, resource);
    }

    private static final class Resource implements IAEKey {

        private final int id;
        private final int variant;

        Resource(int id, int variant) {
            this.id = id;
            this.variant = variant;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Resource && id == ((Resource) other).id && variant == ((Resource) other).variant;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public boolean web$isSameType(IAEKey other) {
            // Legacy fluid type checks ignore NBT, unlike native resource equality.
            return other instanceof Resource && id == ((Resource) other).id;
        }

        @Override
        public StableItemKey web$getStableKey() {
            throw new AssertionError("Tracking must not encode stable resource IDs");
        }

        @Override
        public IAEKey web$copyIdentity() {
            return this;
        }

        @Override
        public String web$getItemID() {
            return "test:resource";
        }

        @Override
        public String web$getDisplayName() {
            return "Resource";
        }

        @Override
        public boolean web$isCraftable(IAEGrid grid) {
            return true;
        }
    }

    private static final class EqualCpu implements ICraftingCPUCluster {

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
            return null;
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
