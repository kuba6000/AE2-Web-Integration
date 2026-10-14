package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

class StorageCaptureTest {

    @Test
    void flowFlushDoesNotReadNativeStockAndViewerSamplesDoNotSplitFlowBuckets() {
        StorageCapture capture = new StorageCapture(10);
        IAEKey key = resource(new AtomicInteger());
        int token = capture.prepareResource(key);
        capture.recordFlow(token, 12, 3);
        StorageObservation live = capture.observe(grid(), null, 1000, true, false);
        assertTrue(live.stockObserved());
        assertTrue(
            live.flows()
                .isEmpty());
        capture.recordFlow(token, 8, 2);
        StorageObservation flows = capture.flushFlow(5000);
        assertFalse(flows.stockObserved());
        assertFalse(flows.full());
        assertTrue(
            flows.rows()
                .isEmpty());
        assertEquals(
            20,
            flows.flows()
                .get(0)
                .input());
        assertEquals(
            5,
            flows.flows()
                .get(0)
                .output());
        assertTrue(
            capture.flushFlow(10000)
                .flows()
                .isEmpty());
    }

    @Test
    void observationPreservesIdentityFailureWhileTransferAdmissionDoesNotThrow() {
        IllegalStateException cause = new IllegalStateException("Broken resource identity");
        IAEKey key = (IAEKey) Proxy
            .newProxyInstance(IAEKey.class.getClassLoader(), new Class<?>[] { IAEKey.class }, (proxy, method, args) -> {
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                if (method.getName()
                    .equals("web$supportsStableIdentity")) return true;
                if (method.getName()
                    .equals("web$copyTrackingIdentity")) throw cause;
                throw new AssertionError("Unexpected native operation " + method.getName());
            });
        StorageCapture capture = new StorageCapture(10);
        assertEquals(-1, capture.prepareResource(key));
        IllegalStateException observed = assertThrows(
            IllegalStateException.class,
            () -> capture.observe(grid(Collections.singletonList(key)), null, 1000, true));
        assertSame(cause, observed);
        assertTrue(capture.requiresReconciliation());
    }

    @Test
    void unsupportedResourcesRemainVisibleWithoutKeysCopiesFlowOrCoverageLoss() {
        IAEKey unsupported = unsupported(false);
        AtomicLong amount = new AtomicLong(42);
        List<IAEGenericStack> stacks = new ArrayList<>();
        stacks.add(new IAEGenericStack() {

            public @NotNull IAEKey web$what() {
                return unsupported;
            }

            public long web$amount() {
                return amount.get();
            }
        });
        IAEGrid grid = grid(Collections.emptyList(), stacks);
        StorageCapture capture = new StorageCapture(1);
        capture.prepareResource(resource(new AtomicInteger()));
        int token = capture.prepareResource(unsupported);
        capture.recordFlow(token, 10, 3);
        StorageObservation baseline = capture.observe(grid, null, 1000, true);
        assertTrue(baseline.flowComplete());
        assertTrue(
            baseline.flows()
                .isEmpty());
        assertNull(
            baseline.rows()
                .get(0)
                .resource()
                .itemKey());
        assertEquals(
            42,
            baseline.rows()
                .get(0)
                .resource()
                .quantity());
        amount.set(7);
        StorageObservation update = capture.observe(grid, null, 2000, false);
        assertFalse(update.full());
        assertEquals(
            7,
            update.rows()
                .get(0)
                .resource()
                .quantity());
        stacks.clear();
        assertTrue(
            capture.observe(grid, null, 3000, false)
                .rows()
                .isEmpty());
    }

    @Test
    void unsupportedNativeCopyIsAnUnkeyedDisplayResourceNotACapacityFailure() {
        IAEKey unsupported = unsupported(true);
        StorageCapture capture = new StorageCapture(10);
        capture.recordFlow(capture.prepareResource(unsupported), 10, 0);
        StorageObservation result = capture.observe(grid(Collections.singletonList(unsupported)), null, 1000, true);
        assertTrue(result.flowComplete());
        assertTrue(
            result.flows()
                .isEmpty());
        assertNull(
            result.rows()
                .get(0)
                .resource()
                .itemKey());
    }

    @Test
    void anUnavailableEncoderKeepsDisplayButDropsUnidentifiableFlow() {
        IAEKey metadata = resource(new AtomicInteger());
        IAEKey unencodable = (IAEKey) Proxy
            .newProxyInstance(IAEKey.class.getClassLoader(), new Class<?>[] { IAEKey.class }, (proxy, method, args) -> {
                if (method.getName()
                    .equals("web$getKey")) throw new IllegalArgumentException("Native NBT cannot be encoded");
                if (method.getName()
                    .equals("web$copyTrackingIdentity")
                    || method.getName()
                        .equals("web$copyIdentity"))
                    return proxy;
                return method.invoke(metadata, args);
            });
        IAEGenericStack stack = new IAEGenericStack() {

            public @NotNull IAEKey web$what() {
                return unencodable;
            }

            public long web$amount() {
                return 21;
            }
        };
        IAEGrid grid = grid(Collections.emptyList(), Collections.singletonList(stack));
        StorageCapture capture = new StorageCapture(10);
        assertTrue(metadata.web$supportsStableIdentity());
        capture.recordFlow(capture.prepareResource(unencodable), Long.MAX_VALUE, 3);
        capture.recordFlow(capture.prepareResource(unencodable), 1, 0);
        StorageObservation observed = capture.observe(grid, null, 1000, true);
        assertTrue(observed.flowComplete());
        assertTrue(
            observed.flows()
                .isEmpty());
        assertNull(
            observed.rows()
                .get(0)
                .resource()
                .itemKey());
        assertEquals(
            21,
            observed.rows()
                .get(0)
                .resource()
                .quantity());
        capture.recordFlow(capture.prepareResource(unencodable), 2, 0);
        StorageObservation update = capture.observe(grid, null, 2000, false);
        assertEquals(
            1,
            update.rows()
                .size());
        assertTrue(
            update.flows()
                .isEmpty());
        assertTrue(update.flowComplete());
    }

    @Test
    void supportedFlowOverflowStillMarksIncompleteCoverage() {
        StorageCapture capture = new StorageCapture(10);
        int token = capture.prepareResource(resource(new AtomicInteger()));
        capture.recordFlow(token, Long.MAX_VALUE, 0);
        capture.recordFlow(token, 1, 0);
        StorageObservation result = capture.observe(grid(), null, 1000, true);
        assertFalse(result.flowComplete());
        assertEquals(
            Long.MAX_VALUE,
            result.flows()
                .get(0)
                .input());
    }

    static IAEKey unsupported(boolean claimsSupport) {
        return (IAEKey) Proxy
            .newProxyInstance(IAEKey.class.getClassLoader(), new Class<?>[] { IAEKey.class }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "web$supportsStableIdentity":
                        return claimsSupport;
                    case "web$copyTrackingIdentity":
                        if (claimsSupport) throw new UnsupportedOperationException("No native copy");
                        throw new AssertionError("Unsupported resource must not be copied");
                    case "web$getKey":
                        throw new AssertionError("Unsupported resource must not be hashed");
                    case "web$getRegistryNamespace":
                        return "test";
                    case "web$getRegistryPath":
                        return "essentia";
                    case "web$getDisplayName":
                        return "Essentia";
                    case "web$getResourceType":
                        return ResourceType.OTHER;
                    case "web$getComponentCount":
                    case "web$getDamage":
                        return 0;
                    case "web$isCraftable":
                        return true;
                    default:
                        throw new AssertionError("Unexpected operation " + method.getName());
                }
            });
    }

    @Test
    void reachingCapacityDoesNotAttemptAnotherNativeCopy() {
        AtomicInteger firstCopies = new AtomicInteger();
        AtomicInteger otherCopies = new AtomicInteger();
        StorageCapture capture = new StorageCapture(1);
        capture.prepareResource(resource(firstCopies));
        IAEKey other = resource(otherCopies);
        assertThrows(
            IllegalStateException.class,
            () -> capture.observe(grid(Collections.singletonList(other)), null, 1000, true));
        assertEquals(1, firstCopies.get());
        assertEquals(0, otherCopies.get());
    }

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
        return grid(craftables, Collections.emptyList());
    }

    static IAEGrid grid(List<IAEKey> craftables, List<IAEGenericStack> stacks) {
        IStackList list = new IStackList() {

            public long web$getAmount(IAEKey key) {
                return 0;
            }

            public Iterable<IAEGenericStack> web$stacks() {
                return stacks;
            }
        };
        IAEStorageGrid storage = new IAEStorageGrid() {

            private StorageCapture capture;

            public IStackList web$getStorageList() {
                return list;
            }

            public IAEMeInventoryItem web$getInventory() {
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
                .equals("web$isCurrentlyCraftable") ? craftables.contains(args[0]) : new HashSet<>(craftables));
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

            public @NotNull ResourceType web$getResourceType() {
                return ResourceType.ITEM;
            }

            public @NotNull StableKey web$getKey() {
                return itemKey;
            }

            public @NotNull IAEKey web$copyIdentity() {
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

            public @NotNull String web$getDisplayName() {
                return "Item";
            }

            public boolean web$isCraftable(IAEGrid grid) {
                return false;
            }
        };
    }
}
