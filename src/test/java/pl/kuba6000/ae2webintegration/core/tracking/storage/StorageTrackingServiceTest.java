package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

class StorageTrackingServiceTest {

    @Test
    void unsampledRemovalKeepsDescriptionUntilTheZeroSampleIsPersisted() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(grid, 1, observation(item, 1000, 5), true, false);
            awaitRevision(service, grid, 1);
            service.submit(
                grid,
                1,
                StorageObservationFixtures
                    .observation(2000, true, true, Collections.emptyList(), Collections.emptyList()),
                false,
                false);
            awaitRevision(service, grid, 2);
            service.submit(
                grid,
                1,
                StorageObservationFixtures
                    .observation(3000, true, true, Collections.emptyList(), Collections.emptyList()),
                true,
                false);
            awaitRevision(service, grid, 3);
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
            assertFalse(service.submit(grid, 1, observation, true));
        }
    }

    @Test
    void coverageLossMarksHistoryIncompleteUntilAFullObservation() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(grid, 1, observation(item, 1000, 5), true, false);
            awaitRevision(service, grid, 1);
            service.invalidate(grid, 1);
            assertFalse(snapshot(service, grid).stockComplete());
            service.submit(grid, 1, observation(item, 2000, 9), true, false);
            awaitRevision(service, grid, 2);
            assertTrue(snapshot(service, grid).stockComplete());
            assertEquals(
                9,
                snapshot(service, grid).rows()
                    .get(0)
                    .quantity());
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
        service.submit(grid, 1, observation(StableKey.random(), 1000, 5), true, false);
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
            service.submit(grid, 1, observation(item, 1000, 5), true, false);
            awaitRevision(service, grid, 1);
            service.submit(grid, 1, observation(item, 1250, 8), false, false);
            awaitRevision(service, grid, 2);
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
                true);
            service.submit(grid, 1, observation(item, 10000, 8), true, false);
            awaitRevision(service, grid, 3);
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
                true);
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
    void unkeyedRowsAreEphemeralDisplayStateAndNeverHistoryOrFlow() throws Exception {
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
                true);
            awaitRevision(service, grid, 1);
            assertEquals(
                1,
                snapshot(service, grid).rows()
                    .size());
            assertEquals(
                1,
                snapshot(service, grid).unkeyed()
                    .size());
            assertTrue(snapshot(service, grid).flowComplete());
            service.submit(
                grid,
                1,
                StorageObservationFixtures.observation(
                    2000,
                    false,
                    true,
                    Collections.singletonList(new StorageObservationFixtures.Row(updated, false)),
                    Collections.emptyList()),
                true);
            awaitRevision(service, grid, 2);
            assertEquals(
                1,
                snapshot(service, grid).rows()
                    .size());
            assertEquals(
                1,
                snapshot(service, grid).unkeyed()
                    .size());
            assertEquals(
                7,
                snapshot(service, grid).unkeyed()
                    .get(0)
                    .resource()
                    .quantity());
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
                true);
            awaitRevision(service, grid, 3);
            assertTrue(
                snapshot(service, grid).unkeyed()
                    .isEmpty());
            assertEquals(
                1,
                snapshot(service, grid).rows()
                    .size());
            service.submit(
                grid,
                1,
                StorageObservationFixtures
                    .observation(3000, false, true, Collections.emptyList(), Collections.emptyList()),
                true);
            awaitRevision(service, grid, 4);
            assertEquals(
                1,
                snapshot(service, grid).rows()
                    .size());
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
            assertTrue(service.submit(grid, 1, observation(item, 1000, 50), true));
            awaitRevision(service, grid, 1);
            assertEquals(
                50,
                snapshot(service, grid).rows()
                    .get(0)
                    .quantity());
            assertTrue(
                service.submit(
                    grid,
                    1,
                    StorageObservationFixtures
                        .observation(2000, true, true, Collections.emptyList(), Collections.emptyList()),
                    true));
            awaitRevision(service, grid, 2);
            assertTrue(
                snapshot(service, grid).rows()
                    .isEmpty());
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
            }

            public @NotNull StorageHistory read(@NotNull StableKey key, @Nullable StableKey resource, long from,
                int limit) {
                return new StorageHistory(Collections.emptyList(), Collections.emptyMap(), true, false);
            }
        };
        try (StorageTrackingService service = new StorageTrackingService(blocked, 4)) {
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 10, 5), true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(service.submit(grid, 1, observation(item, 15, 7), true));
            assertFalse(service.submit(grid, 1, observation(item, 20, 9), true));
            release.countDown();
            awaitRevision(service, grid, 2);
            assertFalse(snapshot(service, grid).stockComplete());
            assertFalse(snapshot(service, grid).flowComplete());
            assertTrue(service.submit(grid, 1, observation(item, 30, 9), true));
            awaitRevision(service, grid, 3);
            assertTrue(snapshot(service, grid).stockComplete());
            assertFalse(snapshot(service, grid).flowComplete());
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
            assertTrue(service.submit(grid, 1, observation(item, 10, 5), false));
            awaitRevision(service, grid, 1);
            service.open(grid, 2);
            assertFalse(service.submit(grid, 1, observation(item, 20, 100), true));
            assertEquals(0, snapshot(service, grid).revision());
            assertTrue(
                snapshot(service, grid).rows()
                    .isEmpty());
            service.retire(grid, 1);
            assertNotNull(service.snapshot(grid));
            service.retire(grid, 2);
            assertNull(service.snapshot(grid));
        }
    }

    @Test
    void fullSnapshotExplicitZeroProducesOnlyOneRemovalPoint() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100)) {
            service.open(grid, 1);
            service.submit(grid, 1, observation(item, 10, 5), true);
            awaitRevision(service, grid, 1);
            service.submit(grid, 1, observation(item, 20, 0), true);
            awaitRevision(service, grid, 2);
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
            service.submit(grid, 1, first, true);
            awaitRevision(service, grid, 1);
            service.submit(
                grid,
                1,
                new StorageObservation(
                    20,
                    false,
                    true,
                    first.rows(),
                    Collections.singletonList(new StorageObservation.Flow(item, 30, 30)),
                    first.descriptions(),
                    Collections.emptyList()),
                true);
            awaitRevision(service, grid, 2);
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
            assertEquals(
                5,
                snapshot(service, grid).rows()
                    .get(0)
                    .quantity());
        }
    }

    @Test
    void retirementAndShutdownKeepAcceptedDetachedHistoryWithoutPublishingOldGeneration() {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        InMemoryStorageHistoryStore store = new InMemoryStorageHistoryStore();
        StorageTrackingService service = new StorageTrackingService(store, 100);
        service.open(grid, 1);
        assertTrue(service.submit(grid, 1, observation(item, 10, 5), true));
        service.retire(grid, 1);
        assertNull(service.snapshot(grid));
        service.open(grid, 2);
        assertTrue(service.submit(grid, 2, observation(item, 20, 7), true));
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
        assertFalse(service.submit(grid, 2, observation(item, 30, 9), true));
        assertNull(service.snapshot(grid));
    }

    @Test
    void persistenceFailureKeepsPublishingButOnlyANewGenerationResumesRecording() throws Exception {
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
            service.submit(grid, 1, observation(item, 10, 5), true);
            awaitRevision(service, grid, 1);
            assertEquals(
                5,
                snapshot(service, grid).rows()
                    .get(0)
                    .quantity());
            assertFalse(snapshot(service, grid).persistenceComplete());
            assertFalse(
                service.history(grid, item, 0, 10)
                    .complete());
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 20, 7), true));
            awaitRevision(service, grid, 2);
            assertEquals(
                7,
                snapshot(service, grid).rows()
                    .get(0)
                    .quantity());
            assertFalse(snapshot(service, grid).persistenceComplete());
            assertTrue(
                service.history(grid, item, 0, 100)
                    .points()
                    .isEmpty());

            service.open(grid, 2);
            assertTrue(service.submit(grid, 2, observation(item, 30, 7), true));
            awaitRevision(service, grid, 1);
            assertTrue(snapshot(service, grid).persistenceComplete());
            List<StorageHistoryPoint> resumed = service.history(grid, item, 0, 100)
                .points();
            assertEquals(4, resumed.size());
            assertTrue(
                resumed.stream()
                    .allMatch(point -> point.generation() == 2 && point.observedAt() == 30));
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
            service.submit(grid, 1, observation(item, 10, 5), false);
            awaitRevision(service, grid, 1);
            service.submit(grid, 1, observation(item, 20, 5), true);
            awaitRevision(service, grid, 2);
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

    private static @NotNull StorageSnapshot snapshot(StorageTrackingService service, StableKey grid) {
        StorageSnapshot snapshot = service.snapshot(grid);
        assertNotNull(snapshot, "Opened grid should have a snapshot");
        return snapshot;
    }

    static void awaitRevision(StorageTrackingService service, StableKey grid, long revision) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (snapshot(service, grid).revision() < revision && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(revision, snapshot(service, grid).revision());
    }
}
