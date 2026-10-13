package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

class FileStorageHistoryStoreTest {

    @TempDir
    Path directory;

    @Test
    void historySurvivesReopeningWithExactAmountsAndResourceIdentity() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        ResourceSnapshot resource = new ResourceSnapshot(
            "test",
            "item",
            2,
            3,
            "Item",
            ResourceType.ITEM,
            Long.MAX_VALUE,
            item,
            null);
        StorageHistoryPoint point = new StorageHistoryPoint(
            1000,
            7,
            StorageHistoryPoint.Kind.STOCK,
            resource,
            true,
            0,
            0,
            true);
        new FileStorageHistoryStore(directory, 1048576).append(grid, Collections.singletonList(point));
        StorageHistory history = new FileStorageHistoryStore(directory, 1048576).read(grid, item, 0, 10);
        assertTrue(history.complete());
        assertEquals(Collections.singletonList(point), history.points());
    }

    @Test
    void aTornTailPreservesEarlierPointsAndRejectsFurtherAppends() throws Exception {
        StableKey grid = StableKey.random();
        FileStorageHistoryStore store = new FileStorageHistoryStore(directory, 1048576);
        StorageHistoryPoint point = new StorageHistoryPoint(
            10,
            1,
            StorageHistoryPoint.Kind.OBSERVATION,
            null,
            false,
            0,
            0,
            true);
        store.append(grid, Collections.singletonList(point));
        Files.write(directory.resolve(grid + ".history"), new byte[] { 1, 2 }, StandardOpenOption.APPEND);
        StorageHistory history = store.read(grid, null, 0, 10);
        assertFalse(history.complete());
        assertEquals(Collections.singletonList(point), history.points());
        assertThrows(IOException.class, () -> store.append(grid, Collections.singletonList(point)));
    }

    @Test
    void hittingDiskCapRemainsVisibleAfterReopeningWithoutDeletingHistory() throws Exception {
        StableKey grid = StableKey.random();
        StorageHistoryPoint point = new StorageHistoryPoint(
            10,
            1,
            StorageHistoryPoint.Kind.OBSERVATION,
            null,
            false,
            0,
            0,
            true);
        FileStorageHistoryStore store = new FileStorageHistoryStore(directory, 300);
        store.append(grid, Collections.singletonList(point));
        assertThrows(IOException.class, () -> store.append(grid, java.util.Arrays.asList(point, point, point)));
        StorageHistory history = new FileStorageHistoryStore(directory, 300).read(grid, null, 0, 10);
        assertEquals(Collections.singletonList(point), history.points());
        assertFalse(history.complete());
    }

    @Test
    void queryLimitsAreExplicitAndTimeFilteringDoesNotDiscardCoverageMarkers() throws Exception {
        StableKey grid = StableKey.random();
        FileStorageHistoryStore store = new FileStorageHistoryStore(directory, 1048576);
        StorageHistoryPoint old = new StorageHistoryPoint(
            10,
            1,
            StorageHistoryPoint.Kind.OBSERVATION,
            null,
            false,
            0,
            0,
            true);
        StorageHistoryPoint gap = new StorageHistoryPoint(
            20,
            1,
            StorageHistoryPoint.Kind.GAP,
            null,
            false,
            0,
            0,
            false);
        store.append(grid, java.util.Arrays.asList(old, gap, gap));
        StorageHistory result = store.read(grid, StableKey.random(), 20, 1);
        assertEquals(Collections.singletonList(gap), result.points());
        assertFalse(result.complete());
        assertTrue(result.truncated());
    }

    @Test
    void cappedQueriesReturnLatestPointsInChronologicalOrder() throws Exception {
        StableKey grid = StableKey.random();
        FileStorageHistoryStore store = new FileStorageHistoryStore(directory, 1048576);
        StorageHistoryPoint first = new StorageHistoryPoint(
            10,
            1,
            StorageHistoryPoint.Kind.OBSERVATION,
            null,
            false,
            0,
            0,
            true);
        StorageHistoryPoint second = new StorageHistoryPoint(
            20,
            1,
            StorageHistoryPoint.Kind.OBSERVATION,
            null,
            false,
            0,
            0,
            true);
        StorageHistoryPoint third = new StorageHistoryPoint(
            30,
            1,
            StorageHistoryPoint.Kind.OBSERVATION,
            null,
            false,
            0,
            0,
            true);
        store.append(grid, java.util.Arrays.asList(first, second, third));
        StorageHistory latest = store.read(grid, null, 0, 2);
        assertEquals(java.util.Arrays.asList(second, third), latest.points());
        assertTrue(latest.truncated());
    }

    @Test
    void concurrentReadsSeeOnlyCompletedAppendPrefixes() throws Exception {
        StableKey grid = StableKey.random();
        FileStorageHistoryStore store = new FileStorageHistoryStore(directory, 1048576);
        java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> writing = writer.submit(() -> {
                for (int time = 1; time <= 30; time++) {
                    try {
                        store.append(
                            grid,
                            Collections.singletonList(
                                new StorageHistoryPoint(
                                    time,
                                    1,
                                    StorageHistoryPoint.Kind.OBSERVATION,
                                    null,
                                    false,
                                    0,
                                    0,
                                    true)));
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }
            });
            for (int read = 0; read < 30; read++) {
                StorageHistory snapshot = store.read(grid, null, 0, 100);
                assertTrue(snapshot.complete());
                long previous = 0;
                for (StorageHistoryPoint point : snapshot.points()) {
                    assertTrue(point.observedAt() > previous);
                    previous = point.observedAt();
                }
            }
            writing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(
                30,
                store.read(grid, null, 0, 100)
                    .points()
                    .size());
        } finally {
            writer.shutdownNow();
        }
    }
}
