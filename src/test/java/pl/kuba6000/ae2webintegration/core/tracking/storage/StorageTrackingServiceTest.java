package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingTestFixtures.awaitHistory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

class StorageTrackingServiceTest {

    @Test
    void incompleteStockAttemptsRemainGapsUntilFullObservationReestablishesBaseline() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 1000, 5), true, false, 0L));
            drain(service);
            service.invalidate(grid, 1);
            for (int index = 2; index <= 3; index++) {
                assertTrue(
                    service.submit(
                        grid,
                        1,
                        StorageObservationFixtures
                            .observation(index * 1000, false, true, Collections.emptyList(), Collections.emptyList()),
                        true,
                        false,
                        0L));
                drain(service);
                StorageHistory incomplete = service.history(grid, item, index * 1000, 100);
                assertFalse(incomplete.complete());
                assertEquals(
                    Collections.singletonList(StorageHistoryPoint.Kind.GAP),
                    incomplete.points()
                        .stream()
                        .map(StorageHistoryPoint::kind)
                        .collect(Collectors.toList()));
            }
            assertTrue(service.submit(grid, 1, observation(item, 4000, 5), true, false, 0L));
            drain(service);
            StorageHistory recovered = service.history(grid, item, 4000, 100);
            assertTrue(recovered.complete());
            assertTrue(
                recovered.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.STOCK && point.quantity() == 5));
            assertTrue(
                recovered.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.OBSERVATION));
        }
    }

    @Test
    void partialFlowKeepsCountersWithGapInsteadOfSuccessfulObservationMarker() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100)) {
            service.open(grid, 1);
            assertTrue(
                service.submit(
                    grid,
                    1,
                    new StorageObservation(
                        1000,
                        false,
                        false,
                        false,
                        Collections.emptyList(),
                        Collections.singletonList(new StorageObservation.Flow(item, 7, 3, 5, 2)),
                        Collections.singletonMap(item, resource(item, 0).description()),
                        Collections.emptyList()),
                    false,
                    true,
                    0L));
            service.close();
            StorageHistory history = store.read(grid, item, 1000, 100);
            assertFalse(history.complete());
            assertTrue(
                history.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.GAP));
            assertFalse(
                history.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.FLOW_OBSERVATION));
            StorageHistoryPoint flow = history.points()
                .stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Partial flow counters were discarded"));
            assertEquals(7, flow.input());
            assertEquals(5, flow.inputOperations());
            assertEquals(2, flow.outputOperations());
            assertEquals(3, flow.output());
        }
    }

    @Test
    void initialRecipeOnlyZeroRemainsOutsideStockHistoryAcrossGapRecovery() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            List<StorageObservationFixtures.Row> recipe = Collections
                .singletonList(new StorageObservationFixtures.Row(resource(item, 0), true));
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures.observation(1000, true, true, recipe, Collections.emptyList()),
                    true,
                    false,
                    0L));
            drain(service);
            service.invalidate(grid, 1);
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures.observation(2000, true, true, recipe, Collections.emptyList()),
                    true,
                    false,
                    0L));
            drain(service);
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures
                        .observation(3000, true, true, Collections.emptyList(), Collections.emptyList()),
                    true,
                    false,
                    0L));
            drain(service);
            List<StorageHistoryPoint> points = service.history(grid, item, 0, 100)
                .points();
            assertFalse(
                points.stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.STOCK));
            assertEquals(
                3,
                points.stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.OBSERVATION)
                    .count());
        }
    }

    @Test
    void gapRecoveryReestablishesKnownZeroWithoutRecordingRecipeRemovalAgain() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 1000, 5), true, false, 0L));
            drain(service);
            List<StorageObservationFixtures.Row> emptyCraftable = Collections
                .singletonList(new StorageObservationFixtures.Row(resource(item, 0), true));
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures.observation(2000, true, true, emptyCraftable, Collections.emptyList()),
                    true,
                    false,
                    0L));
            drain(service);
            service.invalidate(grid, 1);
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures.observation(3000, true, true, emptyCraftable, Collections.emptyList()),
                    true,
                    false,
                    0L));
            drain(service);
            List<StorageHistoryPoint> recovered = service.history(grid, item, 3000, 100)
                .points()
                .stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                .collect(Collectors.toList());
            assertEquals(1, recovered.size());
            assertEquals(
                0,
                recovered.get(0)
                    .quantity());
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures
                        .observation(4000, true, true, Collections.emptyList(), Collections.emptyList()),
                    true,
                    false,
                    0L));
            drain(service);
            assertFalse(
                service.history(grid, item, 4000, 100)
                    .points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.STOCK));
        }
    }

    @Test
    void recipeChangesDoNotInventStockHistory() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        StableKey recipeOnly = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 1000, 50), true, false, 0L));
            drain(service);
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures.observation(
                        2000,
                        true,
                        true,
                        Arrays.asList(
                            new StorageObservationFixtures.Row(resource(item, 50), true),
                            new StorageObservationFixtures.Row(resource(recipeOnly, 0), true)),
                        Collections.emptyList()),
                    true,
                    false,
                    0L));
            drain(service);
            assertTrue(service.submit(grid, 1, observation(item, 3000, 50), true, false, 0L));
            drain(service);
            List<StorageHistoryPoint> points = service.history(grid, null, 0, 100)
                .points();
            assertEquals(
                1,
                points.stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                    .count());
            assertFalse(
                points.stream()
                    .anyMatch(point -> recipeOnly.equals(point.itemKey())));
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures
                        .observation(4000, true, true, Collections.emptyList(), Collections.emptyList()),
                    true,
                    false,
                    0L));
            drain(service);
            assertEquals(
                Arrays.asList(50L, 0L),
                service.history(grid, item, 0, 100)
                    .points()
                    .stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                    .map(StorageHistoryPoint::quantity)
                    .collect(Collectors.toList()));
        }
    }

    @Test
    void stockSampleAfterFlowOnlyGapReestablishesUnchangedStock() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 10, 5), true, false, 0L));
            drain(service);
            service.invalidate(grid, 1);
            assertTrue(
                service.submit(
                    grid,
                    1,
                    new StorageObservation(
                        20,
                        false,
                        true,
                        false,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyMap(),
                        Collections.emptyList()),
                    false,
                    true,
                    0L));
            assertTrue(service.submit(grid, 1, observation(item, 30, 5), true, false, 0L));
            drain(service);
            assertTrue(
                service.history(grid, item, 0, 100)
                    .points()
                    .stream()
                    .anyMatch(
                        point -> point.kind() == StorageHistoryPoint.Kind.STOCK && point.observedAt() == 30
                            && point.quantity() == 5));
        }
    }

    @Test
    void unsampledRemovalKeepsDescriptionUntilTheZeroSampleIsPersisted() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(grid, 1, observation(item, 1000, 5), true, false, 0L);
            drain(service);
            service.submit(
                grid,
                1,
                StorageObservationFixtures
                    .observation(2000, true, true, Collections.emptyList(), Collections.emptyList()),
                false,
                false,
                0L);
            drain(service);
            service.submit(
                grid,
                1,
                StorageObservationFixtures
                    .observation(3000, true, true, Collections.emptyList(), Collections.emptyList()),
                true,
                false,
                0L);
            drain(service);
            StorageHistory history = service.history(grid, item, 0, 100);
            assertEquals(
                "Item",
                history.descriptions()
                    .get(item)
                    .displayName());
            assertTrue(
                history.points()
                    .stream()
                    .anyMatch(
                        point -> point.kind() == StorageHistoryPoint.Kind.STOCK && point.observedAt() == 3000
                            && point.quantity() == 0));
        }
    }

    @Test
    void unkeyedDisplayRowsConsumeQueueBudget() {
        StableKey grid = StableKey.random();
        ResourceSnapshot unknown = new ResourceSnapshot(
            new ResourceDescriptor(null, null, 0, 0, "Unknown", ResourceType.OTHER, null, null),
            1);
        StorageObservation observation = StorageObservationFixtures.observation(
            1000,
            true,
            true,
            Arrays.asList(
                new StorageObservationFixtures.Row(unknown, false),
                new StorageObservationFixtures.Row(unknown, true)),
            Collections.emptyList());
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 2)) {
            service.open(grid, 1);
            assertFalse(service.submit(grid, 1, observation, true, true, 0L));
        }
    }

    @Test
    void coverageLossMarksHistoryIncompleteUntilAFullObservation() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(grid, 1, observation(item, 1000, 5), true, false, 0L);
            drain(service);
            service.invalidate(grid, 1);
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures
                        .observation(1500, false, true, Collections.emptyList(), Collections.emptyList()),
                    true,
                    false,
                    0L));
            drain(service);
            assertFalse(
                service.history(grid, item, 1500, 100)
                    .complete());
            service.submit(grid, 1, observation(item, 2000, 9), true, false, 0L);
            drain(service);
            StorageHistory recovered = service.history(grid, item, 2000, 100);
            assertTrue(recovered.complete());
            assertTrue(
                recovered.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.STOCK && point.quantity() == 9));
        }
    }

    @Test
    void shutdownClosesTheStoreAfterAcceptedWritesOnTheWorkerThread() throws Exception {
        AtomicBoolean written = new AtomicBoolean();
        AtomicBoolean closedAfterWrite = new AtomicBoolean();
        AtomicReference<Thread> closingThread = new AtomicReference<>();
        IStorageHistoryStore store = new IStorageHistoryStore() {

            public void append(@NotNull StableKey grid, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
                @NotNull List<StorageHistoryPoint> points) {
                written.set(true);
            }

            public @NotNull StorageHistory read(@NotNull StableKey grid, @Nullable StableKey resource, long from,
                int limit) {
                return new StorageHistory(Collections.emptyList(), Collections.emptyMap(), true, false);
            }

            public void close() {
                closedAfterWrite.set(written.get());
                closingThread.set(Thread.currentThread());
            }
        };
        StorageTrackingService service = new StorageTrackingService(store, 100);
        StableKey grid = StableKey.random();
        service.open(grid, 1);
        service.submit(grid, 1, observation(StableKey.random(), 1000, 5), true, false, 0L);
        service.close();
        assertTrue(closedAfterWrite.get());
        assertNotNull(closingThread.get());
        assertNotSame(Thread.currentThread(), closingThread.get());
    }

    @Test
    void liveStockAndFlowBucketsDoNotChangeDurableStockSampling() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(grid, 1, observation(item, 1000, 5), true, false, 0L);
            drain(service);
            service.submit(grid, 1, observation(item, 1250, 8), false, false, 0L);
            drain(service);
            ResourceSnapshot resource = resource(item, 8);
            service.submit(
                grid,
                1,
                StorageObservationFixtures.observation(
                    5000,
                    false,
                    true,
                    false,
                    Collections.emptyList(),
                    Collections.singletonList(new StorageObservationFixtures.Flow(resource, 3, 0))),
                false,
                true,
                0L);
            service.submit(grid, 1, observation(item, 10000, 8), true, false, 0L);
            drain(service);
            List<StorageHistoryPoint> points = service.history(grid, item, 0, 100)
                .points();
            assertEquals(
                2,
                points.stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                    .count());
            assertTrue(
                points.stream()
                    .noneMatch(point -> point.observedAt() == 1250));
            assertTrue(
                points.stream()
                    .anyMatch(
                        point -> point.kind() == StorageHistoryPoint.Kind.STOCK && point.observedAt() == 10000
                            && point.itemKey() != null
                            && point.quantity() == 8));
            assertEquals(
                1,
                points.stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
                    .count());
        }
    }

    @Test
    void flowOnlyBucketsNeverWriteStockSamples() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        ResourceSnapshot resource = resource(item, 8);
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(
                grid,
                1,
                StorageObservationFixtures.observation(
                    1000,
                    false,
                    true,
                    false,
                    Collections.emptyList(),
                    Collections.singletonList(new StorageObservationFixtures.Flow(resource, 3, 0))),
                false,
                true,
                0L);
            service.close();
            StorageHistory history = service.history(grid, item, 0, 100);
            assertTrue(
                history.points()
                    .stream()
                    .noneMatch(point -> point.kind() == StorageHistoryPoint.Kind.STOCK));
            assertTrue(
                history.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.FLOW));
        }
    }

    @Test
    void unkeyedRowsNeverEnterHistoryOrFlow() throws Exception {
        StableKey grid = StableKey.random();
        StableKey supported = StableKey.random();
        StorageObservationFixtures.Row keyed = new StorageObservationFixtures.Row(resource(supported, 5), false);
        ResourceSnapshot unknown = new ResourceSnapshot(
            new ResourceDescriptor("test", "essentia", 0, 0, "Essentia", ResourceType.OTHER, null, null),
            42);
        ResourceSnapshot updated = new ResourceSnapshot(
            new ResourceDescriptor("test", "essentia", 0, 0, "Essentia", ResourceType.OTHER, null, null),
            7);
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(
                grid,
                1,
                StorageObservationFixtures.observation(
                    1000,
                    true,
                    true,
                    Arrays.asList(keyed, new StorageObservationFixtures.Row(unknown, false)),
                    Collections.singletonList(new StorageObservationFixtures.Flow(unknown, 10, 0))),
                true,
                true,
                0L);
            drain(service);
            service.submit(
                grid,
                1,
                StorageObservationFixtures.observation(
                    2000,
                    false,
                    true,
                    Collections.singletonList(new StorageObservationFixtures.Row(updated, false)),
                    Collections.emptyList()),
                true,
                true,
                0L);
            drain(service);
            ResourceSnapshot removed = new ResourceSnapshot(
                new ResourceDescriptor("test", "essentia", 0, 0, "Essentia", ResourceType.OTHER, null, null),
                0);
            service.submit(
                grid,
                1,
                StorageObservationFixtures.observation(
                    2500,
                    false,
                    true,
                    Collections.singletonList(new StorageObservationFixtures.Row(removed, false)),
                    Collections.emptyList()),
                true,
                true,
                0L);
            drain(service);
            service.submit(
                grid,
                1,
                StorageObservationFixtures
                    .observation(3000, false, true, Collections.emptyList(), Collections.emptyList()),
                true,
                true,
                0L);
            drain(service);
            List<StorageHistoryPoint> history = service.history(grid, null, 0, 100)
                .points();
            assertEquals(
                1,
                history.stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                    .count());
            assertFalse(
                history.stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.FLOW));
            assertTrue(
                history.stream()
                    .allMatch(point -> point.itemKey() == null || supported.equals(point.itemKey())));
        }
    }

    @Test
    void fullObservationRemovesMissingStockWithoutInventingFlow() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        try (StorageTrackingService service = new StorageTrackingService(store, 100)) {
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 1000, 50), true, true, 0L));
            drain(service);
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures
                        .observation(2000, true, true, Collections.emptyList(), Collections.emptyList()),
                    true,
                    true,
                    0L));
            drain(service);
            StorageHistory history = service.history(grid, item, 0, 100);
            StorageHistoryPoint removal = history.points()
                .stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK && point.observedAt() == 2000)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing stock was not recorded as removed"));
            assertEquals(item, removal.itemKey());
            assertEquals(0, removal.quantity());
            assertFalse(
                history.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.FLOW));
        }
    }

    @Test
    void workerBackpressureNeverBlocksProducerAndLaterLossCannotBeClearedByOlderBatch() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorageHistoryStore stored = new InMemoryStorageHistoryStore();
        IStorageHistoryStore blocked = new IStorageHistoryStore() {

            public void append(@NotNull StableKey key, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
                @NotNull List<StorageHistoryPoint> points) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test writer did not resume");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IOException(interrupted);
                }
                stored.append(key, descriptions, points);
            }

            public @NotNull StorageHistory read(@NotNull StableKey key, @Nullable StableKey resource, long from,
                int limit) {
                return stored.read(key, resource, from, limit);
            }
        };
        try (StorageTrackingService service = new StorageTrackingService(blocked, 4)) {
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 10, 5), true, true, 0L));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(service.submit(grid, 1, observation(item, 15, 7), true, true, 0L));
            assertFalse(service.submit(grid, 1, observation(item, 20, 9), true, true, 0L));
            release.countDown();
            drain(service);
            assertEquals(
                Arrays.asList(5L, 7L),
                service.history(grid, item, 0, 100)
                    .points()
                    .stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                    .map(StorageHistoryPoint::quantity)
                    .collect(Collectors.toList()));
            assertTrue(service.submit(grid, 1, observation(item, 30, 9), true, true, 0L));
            drain(service);
            StorageHistory recovery = service.history(grid, item, 30, 100);
            assertFalse(recovery.complete());
            assertTrue(
                recovery.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.GAP));
            assertTrue(
                recovery.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.OBSERVATION));
            assertFalse(
                recovery.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.FLOW_OBSERVATION));
        } finally {
            release.countDown();
        }
    }

    @Test
    void replacingGenerationRejectsOldProducerAndDoesNotInventStockOrFlow() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 10, 5), false, false, 0L));
            drain(service);
            service.open(grid, 2);
            assertFalse(service.submit(grid, 1, observation(item, 20, 100), true, true, 0L));
            assertTrue(
                service.submit(
                    grid,
                    2,
                    StorageObservationFixtures
                        .observation(20, true, true, Collections.emptyList(), Collections.emptyList()),
                    true,
                    true,
                    0L));
            drain(service);
            StorageHistory replacement = service.history(grid, item, 0, 100);
            assertTrue(
                replacement.points()
                    .stream()
                    .allMatch(point -> point.generation() == 2));
            assertFalse(
                replacement.points()
                    .stream()
                    .anyMatch(
                        point -> point.kind() == StorageHistoryPoint.Kind.STOCK
                            || point.kind() == StorageHistoryPoint.Kind.FLOW));
            service.retire(grid, 1);
            assertTrue(service.submit(grid, 2, observation(item, 25, 7), false, false, 0L));
            service.retire(grid, 2);
            assertFalse(service.submit(grid, 2, observation(item, 30, 9), true, true, 0L));
        }
    }

    @Test
    void fullSnapshotExplicitZeroProducesOnlyOneRemovalPoint() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(grid, 1, observation(item, 10, 5), true, true, 0L);
            drain(service);
            service.submit(grid, 1, observation(item, 20, 0), true, true, 0L);
            drain(service);
            assertEquals(
                1,
                service.history(grid, item, 20, 100)
                    .points()
                    .stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                    .count());
        }
    }

    @Test
    void repeatedStockIsNotPersistedAgainButGrossFlowRemainsIndependent() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            StorageObservation first = observation(item, 10, 5);
            service.submit(grid, 1, first, true, true, 0L);
            drain(service);
            service.submit(
                grid,
                1,
                new StorageObservation(
                    20,
                    false,
                    true,
                    first.rows(),
                    Collections.singletonList(new StorageObservation.Flow(item, 30, 30, 1, 1)),
                    first.descriptions(),
                    Collections.emptyList()),
                true,
                true,
                0L);
            drain(service);
            List<StorageHistoryPoint> points = service.history(grid, item, 0, 100)
                .points();
            assertEquals(
                1,
                points.stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                    .count());
            StorageHistoryPoint flow = points.stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Accepted flow was not recorded"));
            assertEquals(30, flow.input());
            assertEquals(30, flow.output());
        }
    }

    @Test
    void retirementAndShutdownKeepAcceptedDetachedHistoryWithoutPublishingOldGeneration() {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        StorageTrackingService service = new StorageTrackingService(store, 100);
        service.open(grid, 1);
        assertTrue(service.submit(grid, 1, observation(item, 10, 5), true, true, 0L));
        service.retire(grid, 1);
        assertFalse(service.submit(grid, 1, observation(item, 25, 9), true, true, 0L));
        service.open(grid, 2);
        assertTrue(service.submit(grid, 2, observation(item, 20, 7), true, true, 0L));
        service.close();
        StorageHistory history = store.read(grid, item, 0, 100);
        assertEquals(
            2,
            history.points()
                .stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                .count());
        assertTrue(
            history.points()
                .stream()
                .anyMatch(point -> point.generation() == 1 && point.kind() == StorageHistoryPoint.Kind.STOCK));
        assertTrue(
            history.points()
                .stream()
                .anyMatch(point -> point.generation() == 2 && point.kind() == StorageHistoryPoint.Kind.GAP));
        assertFalse(service.submit(grid, 2, observation(item, 30, 9), true, true, 0L));
        assertThrows(IllegalStateException.class, () -> service.open(grid, 3));
    }

    @Test
    void persistenceFailureRemainsVisibleUntilTheNextDurableSampleResumesRecording() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        AtomicBoolean failNextWrite = new AtomicBoolean(true);
        InMemoryStorageHistoryStore stored = new InMemoryStorageHistoryStore();
        IStorageHistoryStore failing = new IStorageHistoryStore() {

            public void append(@NotNull StableKey key, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
                @NotNull List<StorageHistoryPoint> points) throws IOException {
                if (failNextWrite.getAndSet(false)) throw new IOException("Disk full");
                stored.append(key, descriptions, points);
            }

            public @NotNull StorageHistory read(@NotNull StableKey key, @Nullable StableKey resource, long from,
                int limit) {
                return stored.read(key, resource, from, limit);
            }
        };
        try (StorageTrackingService service = new StorageTrackingService(failing, 100)) {
            service.open(grid, 1);
            service.submit(grid, 1, observation(item, 10, 5), true, true, 0L);
            drain(service);
            assertFalse(
                service.history(grid, item, 0, 10)
                    .complete());
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 20, 7), false, false, 0L));
            drain(service);
            assertFalse(
                service.history(grid, item, 21, 100)
                    .complete());
            assertTrue(
                service.history(grid, item, 0, 100)
                    .points()
                    .isEmpty());

            assertTrue(service.submit(grid, 1, observation(item, 30, 7), true, true, 0L));
            drain(service);
            assertTrue(
                service.history(grid, item, 31, 100)
                    .complete());
            List<StorageHistoryPoint> resumed = service.history(grid, item, 0, 100)
                .points();
            assertEquals(5, resumed.size());
            assertTrue(
                resumed.stream()
                    .allMatch(point -> point.generation() == 1));
            assertTrue(
                resumed.stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.GAP && point.observedAt() == 10));
            assertTrue(
                resumed.stream()
                    .anyMatch(
                        point -> point.kind() == StorageHistoryPoint.Kind.STOCK && point.quantity() == 7
                            && item.equals(point.itemKey())));
            assertTrue(
                resumed.stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.GAP));
            assertTrue(
                resumed.stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.FLOW_OBSERVATION));
        }
    }

    @Test
    void enablingHistoryAfterALiveViewPersistsAnUnchangedBaseline() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(grid, 1, observation(item, 10, 5), false, false, 0L);
            drain(service);
            service.submit(grid, 1, observation(item, 20, 5), true, true, 0L);
            drain(service);
            List<StorageHistoryPoint> points = service.history(grid, item, 0, 100)
                .points();
            assertEquals(
                1,
                points.stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.STOCK)
                    .count());
            assertTrue(
                points.stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.GAP));
            assertTrue(
                points.stream()
                    .allMatch(point -> point.observedAt() == 20));
        }
    }

    private static ResourceSnapshot resource(StableKey item, long quantity) {
        return new ResourceSnapshot(
            new ResourceDescriptor("test", "item", 0, 0, "Item", ResourceType.ITEM, item, null),
            quantity);
    }

    static StorageObservation observation(StableKey item, long time, long quantity) {
        return StorageObservationFixtures.observation(
            time,
            true,
            true,
            Collections.singletonList(new StorageObservationFixtures.Row(resource(item, quantity), false)),
            Collections.emptyList());
    }

    /** An independent fake-store write fences earlier accepted work, including observations with no durable output. */
    private static void drain(StorageTrackingService service) throws Exception {
        StableKey fence = StableKey.random();
        service.open(fence, 1);
        StorageObservation empty = StorageObservationFixtures
            .observation(0, true, true, Collections.emptyList(), Collections.emptyList());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!service.submit(fence, 1, empty, true, false, 0)) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Worker did not release queue capacity");
            Thread.sleep(1);
        }
        awaitHistory(
            service,
            fence,
            0,
            history -> !history.points()
                .isEmpty());
        service.retire(fence, 1);
    }
}
