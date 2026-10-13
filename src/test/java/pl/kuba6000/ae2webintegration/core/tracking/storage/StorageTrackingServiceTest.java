package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

class StorageTrackingServiceTest {

    @TempDir
    Path directory;

    @Test
    void unkeyedRowsAreEphemeralDisplayStateAndNeverHistoryOrFlow() throws Exception {
        StableKey grid = StableKey.random();
        StableKey supported = StableKey.random();
        StorageObservation.Row keyed = observation(supported, 1000, 5).rows()
            .get(0);
        ResourceSnapshot unknown = new ResourceSnapshot(
            "test",
            "essentia",
            0,
            0,
            "Essentia",
            ResourceType.OTHER,
            42,
            null,
            null);
        ResourceSnapshot updated = new ResourceSnapshot(
            "test",
            "essentia",
            0,
            0,
            "Essentia",
            ResourceType.OTHER,
            7,
            null,
            null);
        try (StorageTrackingService service = new StorageTrackingService(
            new FileStorageHistoryStore(directory, 1048576),
            100)) {
            service.open(grid, 1);
            service.submit(
                grid,
                1,
                new StorageObservation(
                    1000,
                    true,
                    true,
                    java.util.Arrays.asList(keyed, new StorageObservation.Row(unknown, false)),
                    Collections.singletonList(new StorageObservation.Flow(unknown, 10, 0))),
                true);
            awaitRevision(service, grid, 1);
            assertEquals(
                2,
                snapshot(service, grid).rows()
                    .size());
            assertTrue(snapshot(service, grid).flowComplete());
            service.submit(
                grid,
                1,
                new StorageObservation(
                    2000,
                    false,
                    true,
                    Collections.singletonList(new StorageObservation.Row(updated, false)),
                    Collections.emptyList()),
                true);
            awaitRevision(service, grid, 2);
            assertEquals(
                2,
                snapshot(service, grid).rows()
                    .size());
            assertEquals(
                7,
                snapshot(service, grid).rows()
                    .stream()
                    .filter(
                        row -> row.resource()
                            .itemKey() == null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Unkeyed display row missing"))
                    .resource()
                    .quantity());
            ResourceSnapshot removed = new ResourceSnapshot(
                "test",
                "essentia",
                0,
                0,
                "Essentia",
                ResourceType.OTHER,
                0,
                null,
                null);
            service.submit(
                grid,
                1,
                new StorageObservation(
                    2500,
                    false,
                    true,
                    Collections.singletonList(new StorageObservation.Row(removed, false)),
                    Collections.emptyList()),
                true);
            awaitRevision(service, grid, 3);
            assertEquals(
                1,
                snapshot(service, grid).rows()
                    .size());
            service.submit(
                grid,
                1,
                new StorageObservation(3000, false, true, Collections.emptyList(), Collections.emptyList()),
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
                    .allMatch(
                        point -> point.resource() == null || point.resource()
                            .itemKey() != null));
        }
    }

    @Test
    void fullObservationRemovesMissingStockWithoutInventingFlow() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        FileStorageHistoryStore store = new FileStorageHistoryStore(directory, 1048576);
        try (StorageTrackingService service = new StorageTrackingService(store, 100)) {
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, observation(item, 1000, 50), true));
            awaitRevision(service, grid, 1);
            assertEquals(
                50,
                snapshot(service, grid).rows()
                    .get(0)
                    .resource()
                    .quantity());
            assertTrue(
                service.submit(
                    grid,
                    1,
                    new StorageObservation(2000, true, true, Collections.emptyList(), Collections.emptyList()),
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
            ResourceSnapshot removed = removal.resource();
            assertNotNull(removed);
            assertEquals(0, removed.quantity());
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

            public void append(@NotNull StableKey key, @NotNull List<StorageHistoryPoint> points) throws IOException {
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
                return new StorageHistory(Collections.emptyList(), true, false);
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
        try (StorageTrackingService service = new StorageTrackingService(
            new FileStorageHistoryStore(directory, 1048576),
            100)) {
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
        try (StorageTrackingService service = new StorageTrackingService(
            new FileStorageHistoryStore(directory, 1048576),
            100)) {
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
        try (StorageTrackingService service = new StorageTrackingService(
            new FileStorageHistoryStore(directory, 1048576),
            100)) {
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
                    Collections.singletonList(
                        new StorageObservation.Flow(
                            first.rows()
                                .get(0)
                                .resource(),
                            30,
                            30))),
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
                    .resource()
                    .quantity());
        }
    }

    @Test
    void retirementAndShutdownKeepAcceptedDetachedHistoryWithoutPublishingOldGeneration() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        FileStorageHistoryStore store = new FileStorageHistoryStore(directory, 1048576);
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
    void persistenceFailureDoesNotHideLiveStateButReportsIncompleteHistory() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        IStorageHistoryStore failing = new IStorageHistoryStore() {

            public void append(@NotNull StableKey key, @NotNull List<StorageHistoryPoint> points) throws IOException {
                throw new IOException("Disk full");
            }

            public @NotNull StorageHistory read(@NotNull StableKey key, @Nullable StableKey resource, long from,
                int limit) {
                return new StorageHistory(Collections.emptyList(), true, false);
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
                    .resource()
                    .quantity());
            assertFalse(snapshot(service, grid).persistenceComplete());
            assertFalse(
                service.history(grid, item, 0, 10)
                    .complete());
        }
    }

    @Test
    void enablingHistoryAfterALiveViewPersistsAnUnchangedBaseline() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        try (StorageTrackingService service = new StorageTrackingService(
            new FileStorageHistoryStore(directory, 1048576),
            100)) {
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

    static StorageObservation observation(StableKey item, long time, long quantity) {
        ResourceSnapshot row = new ResourceSnapshot(
            "test",
            "item",
            0,
            0,
            "Item",
            ResourceType.ITEM,
            quantity,
            item,
            null);
        return new StorageObservation(
            time,
            true,
            true,
            Collections.singletonList(new StorageObservation.Row(row, false)),
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
