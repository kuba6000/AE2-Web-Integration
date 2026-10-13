package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

class StorageCaptureTest {

    @Test
    void absoluteNativeBatchesPublishOnlyAfterSuccessfulCompletion() {
        IAEKey key = resource(new AtomicInteger());
        StorageCapture capture = new StorageCapture(10);
        IAEGrid grid = grid();
        capture.observe(grid, null, 0, true);
        assertFalse(capture.requiresReconciliation());
        capture.beginAbsoluteChanges();
        capture.absoluteChanged(key, 73);
        capture.endAbsoluteChanges(true);
        StorageObservation observed = capture.observe(grid, null, 10, false);
        assertFalse(observed.full());
        assertEquals(
            73,
            observed.rows()
                .get(0)
                .resource()
                .quantity());
        capture.beginAbsoluteChanges();
        capture.beginAbsoluteChanges();
        capture.absoluteChanged(key, 999);
        capture.endAbsoluteChanges(false);
        capture.endAbsoluteChanges(true);
        assertTrue(capture.requiresReconciliation());
        assertTrue(
            capture.observe(grid, null, 20, false)
                .rows()
                .isEmpty());
    }

    @Test
    void capacityAndArithmeticOverflowExposeGapsWithoutWrappingCounts() {
        IAEKey first = resource(new AtomicInteger());
        StorageCapture capture = new StorageCapture(1);
        int token = capture.prepareResource(first);
        assertEquals(-1, capture.prepareResource(resource(new AtomicInteger())));
        capture.recordFlow(token, Long.MAX_VALUE, 0);
        capture.recordFlow(token, 1, 0);
        StorageObservation result = capture.observe(grid(), null, 100, true);
        assertFalse(result.flowComplete());
        assertEquals(
            Long.MAX_VALUE,
            result.flows()
                .get(0)
                .input());
    }

    @Test
    void unexpectedProducerCannotReadNativeIdentityAndMarksIncompleteCoverage() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        IAEKey key = resource(copies);
        StorageCapture capture = new StorageCapture(10);
        Thread other = new Thread(() -> {
            assertFalse(capture.isProducerThread());
            capture.prepareResource(key);
            capture.markChanged(key);
            capture.beginAbsoluteChanges();
            capture.endAbsoluteChanges(false);
        });
        other.start();
        other.join();
        assertEquals(0, copies.get());
        assertFalse(
            capture.observe(grid(), null, 100, true)
                .flowComplete());
    }

    @Test
    void fullSnapshotsIncludeCraftableOnlyResourcesAndIncrementalUpdatesPreserveCraftability() {
        IAEKey key = resource(new AtomicInteger());
        StorageCapture capture = new StorageCapture(10);
        IAEGrid grid = grid(Collections.singletonList(key));
        StorageObservation first = capture.observe(grid, null, 0, true);
        assertEquals(
            0,
            first.rows()
                .get(0)
                .resource()
                .quantity());
        assertTrue(
            first.rows()
                .get(0)
                .craftable());
        capture.markChanged(key);
        StorageObservation second = capture.observe(grid, null, 1, false);
        assertTrue(
            second.rows()
                .get(0)
                .craftable());
    }

    @Test
    void acceptedInputAndOutputRemainSeparateFromObservedHoldings() {
        AtomicInteger copies = new AtomicInteger();
        IAEKey resource = resource(copies);
        StorageCapture capture = new StorageCapture(10);
        int token = capture.prepareResource(resource);
        capture.recordFlow(token, 100, 0);
        capture.recordFlow(capture.prepareResource(resource), 0, 40);
        assertEquals(1, copies.get());
        StorageObservation observation = capture.observe(grid(), null, 1000, true);
        assertEquals(
            1,
            observation.flows()
                .size());
        assertEquals(
            100,
            observation.flows()
                .get(0)
                .input());
        assertEquals(
            40,
            observation.flows()
                .get(0)
                .output());
        assertTrue(
            observation.rows()
                .isEmpty());
        assertTrue(observation.flowComplete());
        assertTrue(
            capture.observe(grid(), null, 2000, false)
                .flows()
                .isEmpty());
    }

    static IAEGrid grid() {
        return grid(Collections.emptyList());
    }

    static IAEGrid grid(List<IAEKey> craftables) {
        IStackList list = new IStackList() {

            public long web$getAmount(IAEKey key) {
                return 0;
            }

            public Iterable<pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack> web$stacks() {
                return Collections.emptyList();
            }
        };
        IAEStorageGrid storage = new IAEStorageGrid() {

            private StorageCapture capture;

            public IStackList web$getStorageList() {
                return list;
            }

            public pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem web$getInventory() {
                return null;
            }

            public StorageCapture web$getStorageCapture() {
                return capture;
            }

            public void web$setStorageCapture(StorageCapture value) {
                capture = value;
            }
        };
        IAECraftingGrid crafting = (IAECraftingGrid) Proxy.newProxyInstance(
            IAECraftingGrid.class.getClassLoader(),
            new Class<?>[] { IAECraftingGrid.class },
            (proxy, method, args) -> method.getName()
                .equals("web$isCurrentlyCraftable") ? craftables.contains(args[0])
                    : new java.util.HashSet<>(craftables));
        return (IAEGrid) Proxy.newProxyInstance(
            IAEGrid.class.getClassLoader(),
            new Class<?>[] { IAEGrid.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("web$getStorageGrid")) return storage;
                if (method.getName()
                    .equals("web$getCraftingGrid")) return crafting;
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                return null;
            });
    }

    static IAEKey resource(AtomicInteger copies) {
        StableKey itemKey = StableKey.random();
        return new IAEKey() {

            public ResourceType web$getResourceType() {
                return ResourceType.ITEM;
            }

            public StableKey web$getKey() {
                return itemKey;
            }

            public IAEKey web$copyIdentity() {
                copies.incrementAndGet();
                return this;
            }

            public String web$getRegistryNamespace() {
                return "test";
            }

            public String web$getRegistryPath() {
                return "item";
            }

            public int web$getComponentCount() {
                return 0;
            }

            public int web$getDamage() {
                return 0;
            }

            public String web$getDisplayName() {
                return "Item";
            }

            public boolean web$isCraftable(IAEGrid grid) {
                return false;
            }
        };
    }
}
