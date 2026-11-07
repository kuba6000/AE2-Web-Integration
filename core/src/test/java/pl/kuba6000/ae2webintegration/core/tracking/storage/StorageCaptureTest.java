package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.Range;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
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
    void reloadedResourceLimitPreservesAcceptedFlowAndAllowsLaterAdmission() {
        IAEGrid grid = grid();
        try (StorageCapture capture = new StorageCapture(grid, 2)) {
            capture.setStockNeeded(false);
            IAEKey first = resource(new AtomicInteger());
            IAEKey second = resource(new AtomicInteger());
            capture.recordFlow(first, 3, 0);
            capture.recordFlow(second, 5, 0);
            capture.setMaximumResources(1);
            capture.recordFlow(first, 2, 0);
            StorageObservation observation = capture.flushFlow(10);
            assertTrue(observation.flowComplete());
            assertEquals(
                10,
                observation.flows()
                    .stream()
                    .mapToLong(StorageObservation.Flow::input)
                    .sum());
            capture.setMaximumResources(3);
            capture.recordFlow(first, 1, 0);
            capture.recordFlow(second, 1, 0);
            capture.recordFlow(resource(new AtomicInteger()), 1, 0);
            assertEquals(
                3,
                capture.flushFlow(20)
                    .flows()
                    .size());
        }
    }

    @Test
    void metadataTriggeredStockChangeSurvivesZeroRowCleanup() {
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 1);
        capture.observe(grid, 0, true);
        AtomicReference<IAEKey> changed = new AtomicReference<>();
        IAEKey metadata = resource(new AtomicInteger());
        IAEKey item = (IAEKey) Proxy.newProxyInstance(
            IAEKey.class.getClassLoader(),
            new Class<?>[] { IAEKey.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "web$copyIdentity" -> proxy;
                case "web$getDisplayName" -> {
                    capture.beginAbsoluteChanges();
                    capture.absoluteChanged(changed.get(), 7);
                    capture.endAbsoluteChanges(true);
                    yield "Item";
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.invoke(metadata, args);
            });
        changed.set(item);
        capture.recordFlow(item, 0, 4);
        StorageObservation first = capture.observe(grid, 1, false, false);
        assertEquals(
            1,
            first.rows()
                .size());
        assertEquals(
            0,
            first.rows()
                .get(0)
                .quantity());
        StorageObservation second = capture.observe(grid, 2, false);
        assertFalse(second.full());
        assertEquals(
            1,
            second.rows()
                .size());
        assertEquals(
            item.web$getKey(),
            second.rows()
                .get(0)
                .itemKey());
        assertEquals(
            7,
            second.rows()
                .get(0)
                .quantity());
        assertEquals(
            1,
            second.flows()
                .size());
        assertEquals(
            4,
            second.flows()
                .get(0)
                .output());
    }

    @Test
    void failedFullObservationKeepsFlowForAnAbsentResource() {
        IAEKey item = resource(new AtomicInteger());
        List<IAEKey> craftables = new ArrayList<>(Collections.singletonList(item));
        IAEGrid grid = grid(craftables);
        StorageCapture capture = new StorageCapture(grid, 2);
        capture.observe(grid, 0, true);
        capture.recordFlow(item, 12, 5);
        IAEKey metadata = resource(new AtomicInteger());
        IAEKey broken = (IAEKey) Proxy.newProxyInstance(
            IAEKey.class.getClassLoader(),
            new Class<?>[] { IAEKey.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "web$copyIdentity" -> proxy;
                case "web$getDisplayName" -> throw new IllegalStateException("Native metadata unavailable");
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.invoke(metadata, args);
            });
        craftables.clear();
        craftables.add(broken);
        assertThrows(IllegalStateException.class, () -> capture.observe(grid, 1, true));
        StorageObservation drained = capture.flushFlow(2);
        assertEquals(
            1,
            drained.flows()
                .size());
        StorageObservation.Flow flow = drained.flows()
            .get(0);
        assertEquals(item.web$getKey(), flow.itemKey());
        assertEquals(12, flow.input());
        assertEquals(5, flow.output());
        assertNotNull(
            drained.descriptions()
                .get(flow.itemKey()));
        assertTrue(
            capture.flushFlow(3)
                .flows()
                .isEmpty());
    }

    @Test
    void drainedFlowIdentityIsCollectibleWhileItsGridAndCaptureRemainAlive() throws Exception {
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 1);
        capture.setStockNeeded(false);
        WeakReference<IAEKey> identity = recordTemporaryFlow(capture);
        capture.flushFlow(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (identity.get() != null && System.nanoTime() < deadline) {
            System.gc();
            AE2Controller.itemIdentities.cleanUp();
            Thread.sleep(10);
        }
        assertNull(identity.get());
        capture.recordFlow(resource(new AtomicInteger()), 2, 0);
        assertEquals(
            2,
            capture.flushFlow(2)
                .flows()
                .get(0)
                .input());
        assertNotNull(grid.web$getStorageGrid());
    }

    private static WeakReference<IAEKey> recordTemporaryFlow(StorageCapture capture) {
        IAEKey resource = resource(new AtomicInteger());
        capture.recordFlow(resource, 1, 0);
        return new WeakReference<>(AE2Controller.itemIdentities.resolve(resource.web$getKey()));
    }

    @Test
    void idleFlowCaptureReleasesDrainedResourcesAndReconcilesWhenStockReturns() {
        IAEKey item = resource(new AtomicInteger());
        List<IAEKey> craftables = new ArrayList<>(Collections.singletonList(item));
        IAEGrid grid = grid(craftables);
        StorageCapture capture = new StorageCapture(grid, 1);
        capture.observe(grid, 0, true);
        capture.recordFlow(item, 5, 0);
        capture.setStockNeeded(false);
        assertEquals(
            5,
            capture.flushFlow(1)
                .flows()
                .get(0)
                .input());
        IAEKey second = resource(new AtomicInteger());
        capture.recordFlow(second, 9, 0);
        assertEquals(
            9,
            capture.flushFlow(2)
                .flows()
                .get(0)
                .input());
        craftables.clear();
        craftables.add(second);
        capture.setStockNeeded(true);
        StorageObservation resumed = capture.observe(grid, 3, false);
        assertTrue(resumed.full());
        assertEquals(
            second.web$getKey(),
            resumed.rows()
                .get(0)
                .itemKey());
    }

    @Test
    void stockRemovalWaitsForItsObservationEvenAfterFlowHasDrained() {
        IAEKey item = resource(new AtomicInteger());
        List<IAEKey> craftables = new ArrayList<>(Collections.singletonList(item));
        IAEGrid grid = grid(craftables);
        StorageCapture capture = new StorageCapture(grid, 1);
        capture.observe(grid, 0, true);
        craftables.clear();
        capture.recordFlow(item, 0, 3);
        capture.flushFlow(1);
        StorageObservation changed = capture.observe(grid, 2, false);
        assertEquals(
            item.web$getKey(),
            changed.rows()
                .get(0)
                .itemKey());
        assertEquals(
            0,
            changed.rows()
                .get(0)
                .quantity());
        assertFalse(
            changed.rows()
                .get(0)
                .craftable());
        IAEKey replacement = resource(new AtomicInteger());
        craftables.add(replacement);
        assertEquals(
            replacement.web$getKey(),
            capture.observe(grid, 3, true)
                .rows()
                .get(0)
                .itemKey());
    }

    @Test
    void closedCaptureCannotReacquireIdentitiesFromLateNativeCallbacks() {
        StorageCapture capture = new StorageCapture(grid(), 1);
        capture.close();
        AtomicInteger copies = new AtomicInteger();
        IAEKey item = resource(copies);
        capture.recordFlow(item, 1, 0);
        capture.markChanged(item);
        capture.beginAbsoluteChanges();
        capture.absoluteChanged(item, 1);
        capture.endAbsoluteChanges(true);
        assertEquals(0, copies.get());
        assertTrue(
            capture.flushFlow(1)
                .flows()
                .isEmpty());
    }

    @Test
    void fullReconciliationReleasesAbsentResourcesBeforeAdmittingTheirReplacements() {
        List<IAEKey> craftables = new ArrayList<>();
        IAEGrid grid = grid(craftables);
        StorageCapture capture = new StorageCapture(grid, 2);
        for (int generation = 0; generation < 8; generation++) {
            craftables.clear();
            craftables.add(resource(new AtomicInteger()));
            craftables.add(resource(new AtomicInteger()));
            StorageObservation observation = capture.observe(grid, generation, true);
            assertEquals(
                2,
                observation.rows()
                    .size());
            assertTrue(observation.flowComplete());
            for (StorageObservation.Row row : observation.rows()) {
                assertNotNull(AE2Controller.itemIdentities.resolve(row.itemKey()));
            }
        }
    }

    @Test
    void unsupportedVariantAtCapacityStaysUnkeyedWithoutLosingKnownFlow() {
        IAEKey known = resource(new AtomicInteger());
        IAEKey variant = unsupported(true);
        IAEGrid grid = grid(Arrays.asList(known, variant));
        StorageCapture capture = new StorageCapture(grid, 1);
        capture.recordFlow(known, 4, 0);
        capture.recordFlow(variant, 10, 0);
        StorageObservation observation = capture.observe(grid, 1000, true);
        assertTrue(observation.flowComplete());
        assertEquals(
            1,
            observation.rows()
                .size());
        assertEquals(
            1,
            observation.unkeyed()
                .size());
        assertNull(
            observation.unkeyed()
                .get(0)
                .resource()
                .description()
                .itemKey());
        assertEquals(
            1,
            observation.flows()
                .size());
        assertEquals(
            4,
            observation.flows()
                .get(0)
                .input());
    }

    @Test
    void flowDefersBaseNormalizationAndHistoryRetainsItWithoutAnIconPack(@TempDir Path directory) throws Exception {
        AtomicInteger normalizations = new AtomicInteger();
        StableKey baseKey = StableKey.random();
        IAEKey metadata = resource(new AtomicInteger());
        IAEKey resource = (IAEKey) Proxy
            .newProxyInstance(IAEKey.class.getClassLoader(), new Class<?>[] { IAEKey.class }, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "web$copyIdentity" -> proxy;
                    case "web$getBaseKey" -> {
                        normalizations.incrementAndGet();
                        yield baseKey;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(metadata, args);
                };
            });
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 10);
        capture.recordFlow(resource, 12, 3);
        capture.recordFlow(resource, 8, 2);
        assertEquals(0, normalizations.get(), "Transfers must not normalize native resources");
        long now = System.currentTimeMillis();
        StorageObservation observation = capture.flushFlow(now);
        StableKey itemKey = resource.web$getKey();
        assertEquals(
            baseKey,
            observation.descriptions()
                .get(itemKey)
                .baseKey());
        assertEquals(1, normalizations.get());
        StorageObservation.Flow flow = observation.flows()
            .get(0);
        assertEquals(20, flow.input());
        assertEquals(5, flow.output());
        capture.recordFlow(resource, 4, 1);
        assertSame(
            observation.descriptions()
                .get(itemKey),
            capture.flushFlow(now + 1)
                .descriptions()
                .get(itemKey));
        assertEquals(1, normalizations.get());
        StableKey gridKey = StableKey.random();
        Path database = directory.resolve("history");
        StorageHistoryPoint point = new StorageHistoryPoint(
            now,
            1,
            StorageHistoryPoint.Kind.FLOW,
            itemKey,
            0,
            flow.input(),
            flow.output(),
            flow.inputOperations(),
            flow.outputOperations(),
            (now) - 1000L);
        try (SqliteStorageHistoryStore store = WorldHistoryFixtures.create(database, new Range(1, 365, 30), 100)) {
            WorldHistoryFixtures.register(store, gridKey);
            store.append(gridKey, observation.descriptions(), Collections.singletonList(point));
        }
        AE2Controller.itemIdentities.clear();
        try (SqliteStorageHistoryStore store = WorldHistoryFixtures.create(database, new Range(1, 365, 30), 100)) {
            StorageHistory history = store.read(gridKey, itemKey, now, 10);
            assertEquals(Collections.singletonList(point), history.points());
            assertEquals(
                baseKey,
                history.descriptions()
                    .get(itemKey)
                    .baseKey());
            assertEquals(
                "Item",
                history.descriptions()
                    .get(itemKey)
                    .displayName());
        }
    }

    @Test
    void countsAcceptedPositiveOperationsByDirectionAndResetsWithTheFlowBucket() {
        StorageCapture capture = new StorageCapture(grid(), 10);
        IAEKey resource = resource(new AtomicInteger());
        capture.recordFlow(resource, 8, 0);
        capture.recordFlow(resource, 2, 0);
        capture.recordFlow(resource, 0, 7);
        capture.recordFlow(resource, 0, 0);
        capture.recordFlow(unsupported(false), 10, 3);
        StorageObservation first = capture.flushFlow(1000);
        assertEquals(
            1,
            first.flows()
                .size());
        StorageObservation.Flow flow = first.flows()
            .get(0);
        assertEquals(10, flow.input());
        assertEquals(7, flow.output());
        assertEquals(2, flow.inputOperations());
        assertEquals(1, flow.outputOperations());
        assertTrue(
            capture.flushFlow(2000)
                .flows()
                .isEmpty());
        capture.recordFlow(resource, Long.MAX_VALUE, 0);
        capture.recordFlow(resource, 1, 3);
        StorageObservation overflow = capture.flushFlow(3000);
        assertFalse(overflow.flowComplete());
        assertEquals(
            Long.MAX_VALUE,
            overflow.flows()
                .get(0)
                .input());
        assertEquals(
            2,
            overflow.flows()
                .get(0)
                .inputOperations());
        assertEquals(
            1,
            overflow.flows()
                .get(0)
                .outputOperations());
        capture.recordFlow(resource, 0, 1);
        StorageObservation fresh = capture.flushFlow(4000);
        assertTrue(fresh.flowComplete());
        assertEquals(
            0,
            fresh.flows()
                .get(0)
                .inputOperations());
        assertEquals(
            1,
            fresh.flows()
                .get(0)
                .outputOperations());
    }

    @Test
    void zeroTransferDoesNotReadOrRetainNativeIdentity() {
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 1);
        IAEKey untouched = (IAEKey) Proxy
            .newProxyInstance(IAEKey.class.getClassLoader(), new Class<?>[] { IAEKey.class }, (proxy, method, args) -> {
                throw new AssertionError("Zero transfer must not access native identity");
            });
        capture.recordFlow(untouched, 0, 0);
        StorageObservation observation = capture.flushFlow(1000);
        assertTrue(observation.flowComplete());
        assertTrue(
            observation.flows()
                .isEmpty());
        IAEKey accepted = resource(new AtomicInteger());
        capture.recordFlow(accepted, 4, 0);
        StorageObservation next = capture.flushFlow(2000);
        assertTrue(next.flowComplete());
        assertEquals(
            4,
            next.flows()
                .get(0)
                .input());
        assertEquals(
            accepted.web$getKey(),
            next.flows()
                .get(0)
                .itemKey());
    }

    @Test
    void ambiguousResourcesDoNotPreventHealthyFlowOrUnkeyedStockDisplay() {
        assertAmbiguousResourceIsDiscarded(false);
    }

    @Test
    void inventoryRetentionDiscardsAmbiguousResourcesWithoutFailingTheListing() {
        assertAmbiguousResourceIsDiscarded(true);
    }

    private void assertAmbiguousResourceIsDiscarded(boolean retainFirst) {
        for (boolean describeFirst : new boolean[] { false, true }) {
            StableKey ambiguousKey = StableKey.random();
            IAEKey original = resource(new AtomicInteger(), ambiguousKey);
            IAEKey conflicting = resource(new AtomicInteger(), ambiguousKey);
            IAEKey healthy = resource(new AtomicInteger());
            List<IAEKey> craftables = new ArrayList<>();
            IAEGrid grid = grid(craftables);
            StorageCapture capture = new StorageCapture(grid, 3);
            if (describeFirst) {
                capture.recordFlow(original, 1, 0);
                assertEquals(
                    1,
                    capture.flushFlow(0)
                        .flows()
                        .size());
            }
            capture.recordFlow(original, 7, 0);
            capture.recordFlow(healthy, 2, 3);
            assertDoesNotThrow(() -> capture.recordFlow(conflicting, 1, 0));
            if (retainFirst) {
                ItemIdentityRegistry.Listing listing = AE2Controller.itemIdentities.beginListing(grid);
                listing.commit();
            }
            StorageObservation flow = capture.flushFlow(1000);
            assertFalse(flow.flowComplete());
            assertEquals(
                1,
                flow.flows()
                    .size());
            assertEquals(
                healthy.web$getKey(),
                flow.flows()
                    .get(0)
                    .itemKey());
            assertEquals(
                2,
                flow.flows()
                    .get(0)
                    .input());
            assertEquals(
                3,
                flow.flows()
                    .get(0)
                    .output());
            capture.recordFlow(healthy, 4, 0);
            assertEquals(
                4,
                capture.flushFlow(2000)
                    .flows()
                    .get(0)
                    .input());
            craftables.add(original);
            craftables.add(healthy);
            StorageObservation stock = capture.observe(grid, 3000, true);
            assertEquals(
                1,
                stock.rows()
                    .size());
            assertEquals(
                1,
                stock.unkeyed()
                    .size());
            assertNull(
                stock.unkeyed()
                    .get(0)
                    .resource()
                    .description()
                    .itemKey());
            StableKey healthyKey = healthy.web$getKey();
            assertNotNull(healthyKey);
            assertTrue(
                stock.rows()
                    .stream()
                    .anyMatch(row -> healthyKey.equals(row.itemKey())));
        }
    }

    @Test
    void flowFlushDoesNotReadNativeStockAndViewerSamplesDoNotSplitFlowBuckets() {
        IAEGrid owner = grid();
        StorageCapture capture = new StorageCapture(owner, 10);
        IAEKey key = resource(new AtomicInteger());
        StableKey itemKey = key.web$getKey();
        capture.recordFlow(key, 12, 3);
        StorageObservation live = capture.observe(owner, 1000, true, false);
        assertTrue(live.stockObserved());
        assertTrue(
            live.flows()
                .isEmpty());
        capture.recordFlow(key, 8, 2);
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
        capture.recordFlow(key, 4, 1);
        StorageObservation next = capture.flushFlow(15000);
        assertEquals(
            itemKey,
            next.flows()
                .get(0)
                .itemKey());
        assertEquals(
            4,
            next.flows()
                .get(0)
                .input());
        assertEquals(
            1,
            next.flows()
                .get(0)
                .output());
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
                    .equals("web$copyIdentity")) throw cause;
                throw new AssertionError("Unexpected native operation " + method.getName());
            });
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 10);
        assertDoesNotThrow(() -> capture.recordFlow(key, 1, 0));
        IllegalStateException observed = assertThrows(
            IllegalStateException.class,
            () -> capture.observe(grid(Collections.singletonList(key)), 1000, true));
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
        StorageCapture capture = new StorageCapture(grid, 1);
        capture.markChanged(resource(new AtomicInteger()));
        capture.recordFlow(unsupported, 10, 3);
        StorageObservation baseline = capture.observe(grid, 1000, true);
        assertTrue(baseline.flowComplete());
        assertTrue(
            baseline.flows()
                .isEmpty());
        assertNull(
            baseline.unkeyed()
                .get(0)
                .resource()
                .description()
                .itemKey());
        assertEquals(
            42,
            baseline.unkeyed()
                .get(0)
                .resource()
                .quantity());
        amount.set(7);
        StorageObservation update = capture.observe(grid, 2000, false);
        assertFalse(update.full());
        assertEquals(
            7,
            update.unkeyed()
                .get(0)
                .resource()
                .quantity());
        stacks.clear();
        assertTrue(
            capture.observe(grid, 3000, false)
                .unkeyed()
                .isEmpty());
    }

    @Test
    void unsupportedVariantIsAnUnkeyedDisplayResourceNotACapacityFailure() {
        IAEKey unsupported = unsupported(true);
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 10);
        capture.recordFlow(unsupported, 10, 0);
        StorageObservation result = capture.observe(grid(Collections.singletonList(unsupported)), 1000, true);
        assertTrue(result.flowComplete());
        assertTrue(
            result.flows()
                .isEmpty());
        assertNull(
            result.unkeyed()
                .get(0)
                .resource()
                .description()
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
                    .equals("web$copyIdentity")) return proxy;
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
        StorageCapture capture = new StorageCapture(grid, 10);
        assertTrue(metadata.web$supportsStableIdentity());
        capture.recordFlow(unencodable, Long.MAX_VALUE, 3);
        capture.recordFlow(unencodable, 1, 0);
        StorageObservation observed = capture.observe(grid, 1000, true);
        assertFalse(observed.flowComplete());
        assertTrue(
            observed.flows()
                .isEmpty());
        assertNull(
            observed.unkeyed()
                .get(0)
                .resource()
                .description()
                .itemKey());
        assertEquals(
            21,
            observed.unkeyed()
                .get(0)
                .resource()
                .quantity());
        capture.recordFlow(unencodable, 2, 0);
        StorageObservation update = capture.observe(grid, 2000, false);
        assertEquals(
            1,
            update.unkeyed()
                .size());
        assertTrue(
            update.flows()
                .isEmpty());
        assertFalse(update.flowComplete());
    }

    @Test
    void supportedFlowOverflowStillMarksIncompleteCoverage() {
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 10);
        IAEKey key = resource(new AtomicInteger());
        capture.recordFlow(key, Long.MAX_VALUE, 0);
        capture.recordFlow(key, 1, 0);
        StorageObservation result = capture.observe(grid, 1000, true);
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
                    case "web$copyIdentity":
                        if (claimsSupport) return proxy;
                        throw new AssertionError("Unsupported resource must not be copied");
                    case "web$getKey":
                        return null;
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
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 1);
        IAEKey first = resource(firstCopies);
        capture.markChanged(first);
        IAEKey other = resource(otherCopies);
        assertThrows(IllegalStateException.class, () -> capture.observe(grid(Arrays.asList(first, other)), 1000, true));
        assertEquals(1, firstCopies.get());
        assertEquals(0, otherCopies.get());
    }

    @Test
    void absoluteNativeBatchesPublishOnlyAfterSuccessfulCompletion() {
        IAEKey key = resource(new AtomicInteger());
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 10);
        capture.observe(grid, 0, true);
        assertFalse(capture.requiresReconciliation());
        capture.beginAbsoluteChanges();
        capture.absoluteChanged(key, 73);
        capture.endAbsoluteChanges(true);
        StorageObservation observed = capture.observe(grid, 10, false);
        assertFalse(observed.full());
        assertEquals(
            73,
            observed.rows()
                .get(0)
                .quantity());
        capture.beginAbsoluteChanges();
        capture.beginAbsoluteChanges();
        capture.absoluteChanged(key, 999);
        capture.endAbsoluteChanges(false);
        capture.endAbsoluteChanges(true);
        assertTrue(capture.requiresReconciliation());
        assertTrue(
            capture.observe(grid, 20, false)
                .rows()
                .isEmpty());
    }

    @Test
    void capacityAndArithmeticOverflowExposeGapsWithoutWrappingCounts() {
        IAEKey first = resource(new AtomicInteger());
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 1);
        capture.recordFlow(first, Long.MAX_VALUE, 0);
        assertDoesNotThrow(() -> capture.recordFlow(resource(new AtomicInteger()), 1, 0));
        capture.recordFlow(first, 1, 0);
        StorageObservation result = capture.observe(grid, 100, true);
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
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 10);
        Thread other = new Thread(() -> {
            assertFalse(capture.isProducerThread());
            capture.recordFlow(key, 1, 0);
            capture.markChanged(key);
            capture.beginAbsoluteChanges();
            capture.endAbsoluteChanges(false);
        });
        other.start();
        other.join();
        assertEquals(0, copies.get());
        assertFalse(
            capture.observe(grid, 100, true)
                .flowComplete());
    }

    @Test
    void fullSnapshotsIncludeCraftableOnlyResourcesAndIncrementalUpdatesPreserveCraftability() {
        IAEKey key = resource(new AtomicInteger());
        IAEGrid grid = grid(Collections.singletonList(key));
        StorageCapture capture = new StorageCapture(grid, 10);
        StorageObservation first = capture.observe(grid, 0, true);
        assertEquals(
            0,
            first.rows()
                .get(0)
                .quantity());
        assertTrue(
            first.rows()
                .get(0)
                .craftable());
        capture.markChanged(key);
        StorageObservation second = capture.observe(grid, 1, false);
        assertTrue(
            second.rows()
                .get(0)
                .craftable());
    }

    @Test
    void acceptedInputAndOutputRemainSeparateFromObservedHoldings() {
        AtomicInteger copies = new AtomicInteger();
        IAEKey resource = resource(copies);
        IAEGrid grid = grid();
        StorageCapture capture = new StorageCapture(grid, 10);
        capture.recordFlow(resource, 100, 0);
        capture.recordFlow(resource, 0, 40);
        assertEquals(1, copies.get());
        StorageObservation observation = capture.observe(grid, 1000, true);
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
            capture.observe(grid, 2000, false)
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
        return resource(copies, StableKey.random());
    }

    static IAEKey resource(AtomicInteger copies, StableKey itemKey) {
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
