package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

class HsqlStorageHistoryStoreTest {

    @TempDir
    Path directory;

    @Test
    void reopenPreservesExactValuesAndHistoricalMetadataVersions() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint first = stock(item, "Before", now, Long.MAX_VALUE);
        StorageHistoryPoint second = stock(item, "After", now + 1, 11);
        try (HsqlStorageHistoryStore store = store(100)) {
            store.append(grid, Arrays.asList(first, second));
        }
        try (HsqlStorageHistoryStore store = store(100)) {
            StorageHistory history = store.read(grid, item, now, 10);
            assertEquals(Arrays.asList(first, second), history.points());
            assertTrue(history.complete());
            assertFalse(history.truncated());
        }
    }

    private HsqlStorageHistoryStore store(int rows) {
        return new HsqlStorageHistoryStore(directory.resolve("database/history"), 7, rows);
    }

    @Test
    void rowBudgetRetiresOldestPointsAndContinuesWritingAcrossReopen() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint first = stock(item, "Item", now, 1);
        StorageHistoryPoint second = stock(item, "Item", now + 1, 2);
        StorageHistoryPoint third = stock(item, "Item", now + 2, 3);
        StorageHistoryPoint fourth = stock(item, "Item", now + 3, 4);
        try (HsqlStorageHistoryStore store = store(2)) {
            store.append(grid, Arrays.asList(first, second, third));
            assertEquals(
                Arrays.asList(second, third),
                store.read(grid, item, now, 10)
                    .points());
            assertTrue(
                store.read(grid, item, now, 10)
                    .truncated());
            assertFalse(
                store.read(grid, item, now, 10)
                    .complete());
            store.append(grid, Collections.singletonList(fourth));
        }
        try (HsqlStorageHistoryStore store = store(2)) {
            StorageHistory current = store.read(grid, item, now + 2, 10);
            assertEquals(Arrays.asList(third, fourth), current.points());
            assertTrue(current.complete());
            assertFalse(current.truncated());
        }
    }

    @Test
    void retentionExcludesExpiredPointsAndPreservesRecentOtherGrids() throws Exception {
        StableKey firstGrid = StableKey.random();
        StableKey otherGrid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint expired = stock(item, "Old", now - TimeUnit.DAYS.toMillis(8), 1);
        StorageHistoryPoint recent = stock(item, "Current", now, 2);
        try (HsqlStorageHistoryStore store = store(100)) {
            store.append(firstGrid, Arrays.asList(expired, recent));
            store.append(otherGrid, Collections.singletonList(recent));
            assertEquals(
                Collections.singletonList(recent),
                store.read(firstGrid, null, 0, 10)
                    .points());
            assertTrue(
                store.read(firstGrid, null, 0, 10)
                    .truncated());
            assertEquals(
                Collections.singletonList(recent),
                store.read(otherGrid, null, now, 10)
                    .points());
        }
    }

    private static StorageHistoryPoint stock(StableKey item, String name, long time, long amount) {
        ResourceSnapshot resource = new ResourceSnapshot(
            "test",
            "item",
            2,
            3,
            name,
            ResourceType.ITEM,
            amount,
            item,
            item);
        return new StorageHistoryPoint(time, 7, StorageHistoryPoint.Kind.STOCK, resource, true, 0, 0, true);
    }

    @Test
    void boundedItemQueryKeepsGlobalCoverageAndExactAcceptedFlow() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        StableKey other = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint gap = new StorageHistoryPoint(
            now,
            1,
            StorageHistoryPoint.Kind.GAP,
            null,
            false,
            0,
            0,
            false);
        StorageHistoryPoint flow = new StorageHistoryPoint(
            now + 2,
            1,
            StorageHistoryPoint.Kind.FLOW,
            stock(item, "Item", now, 0).resource(),
            false,
            Long.MAX_VALUE,
            Long.MAX_VALUE - 1,
            true);
        StorageHistoryPoint observation = new StorageHistoryPoint(
            now + 3,
            1,
            StorageHistoryPoint.Kind.FLOW_OBSERVATION,
            null,
            false,
            0,
            0,
            true);
        try (HsqlStorageHistoryStore store = store(100)) {
            store.append(
                grid,
                Arrays.asList(
                    gap,
                    stock(item, "Item", now + 1, 1),
                    stock(other, "Other", now + 1, 99),
                    flow,
                    observation));
            StorageHistory history = store.read(grid, item, now, 2);
            assertEquals(Arrays.asList(flow, observation), history.points());
            assertFalse(history.complete(), "A gap outside the latest result limit must still describe query coverage");
            assertTrue(history.truncated());
            assertTrue(
                store.read(grid, item, now + 2, 2)
                    .complete());
        }
    }

    @Test
    void shorterRetentionImmediatelyAppliesToPreviouslyStoredData() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint old = stock(item, "Old", now - TimeUnit.DAYS.toMillis(2), 1);
        StorageHistoryPoint recent = stock(item, "Recent", now, 2);
        try (HsqlStorageHistoryStore store = store(100)) {
            store.append(grid, Arrays.asList(old, recent));
        }
        try (HsqlStorageHistoryStore store = new HsqlStorageHistoryStore(
            directory.resolve("database/history"),
            1,
            100)) {
            StorageHistory history = store.read(grid, item, 0, 10);
            assertEquals(Collections.singletonList(recent), history.points());
            assertTrue(history.truncated());
            assertFalse(history.complete());
        }
    }

    @Test
    void closingUnusedStoreDoesNotCreateFilesAndClosedStoreRejectsOperations() throws Exception {
        try (HsqlStorageHistoryStore store = store(100)) {
            store.close();
            store.close();
            assertFalse(Files.exists(directory.resolve("database")));
            assertThrows(IOException.class, () -> store.read(StableKey.random(), null, 0, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> store(0));
    }

    @Test
    void unchangedStockRetainsBaselineBeyondDetailedRetentionAndMarkerWindow() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint anchor = stock(item, "Item", now - TimeUnit.DAYS.toMillis(8), 42);
        StorageHistoryPoint marker = new StorageHistoryPoint(
            now,
            7,
            StorageHistoryPoint.Kind.FLOW_OBSERVATION,
            null,
            false,
            0,
            0,
            true);
        try (HsqlStorageHistoryStore store = store(100)) {
            store.append(grid, Arrays.asList(anchor, marker, marker, marker));
            StorageHistory history = store.read(grid, item, now - 1000, 2);
            assertEquals(Arrays.asList(marker, marker), history.points());
            assertEquals(anchor, history.baseline());
            assertTrue(history.truncated());
            assertTrue(history.complete());
        }
        try (HsqlStorageHistoryStore store = store(100)) {
            assertEquals(
                anchor,
                store.read(grid, item, now - 1000, 2)
                    .baseline());
        }
    }

    @Test
    void hardRowBudgetMayRemoveBaselineButNeverInventsZero() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint old = stock(item, "Item", now - TimeUnit.DAYS.toMillis(8), 42);
        StorageHistoryPoint marker = new StorageHistoryPoint(
            now,
            7,
            StorageHistoryPoint.Kind.OBSERVATION,
            null,
            false,
            0,
            0,
            true);
        try (HsqlStorageHistoryStore store = store(2)) {
            store.append(grid, Arrays.asList(old, marker, marker));
            StorageHistory lost = store.read(grid, item, 0, 10);
            assertNull(lost.baseline());
            assertFalse(lost.complete());
            StorageHistoryPoint fresh = stock(item, "Item", now + 1, 45);
            store.append(grid, Collections.singletonList(fresh));
            StorageHistory restored = store.read(grid, item, now + 2, 10);
            assertEquals(fresh, restored.baseline());
        }
    }

    @Test
    void loweredRowBudgetAppliesOnReopenWithoutAnotherWrite() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint first = stock(item, "Item", now, 1);
        StorageHistoryPoint second = stock(item, "Item", now + 1, 2);
        StorageHistoryPoint third = stock(item, "Item", now + 2, 3);
        try (HsqlStorageHistoryStore store = store(100)) {
            store.append(grid, Arrays.asList(first, second, third));
        }
        try (HsqlStorageHistoryStore store = store(2)) {
            StorageHistory history = store.read(grid, item, now, 10);
            assertEquals(Arrays.asList(second, third), history.points());
            assertTrue(history.truncated());
            assertFalse(history.complete());
        }
    }

    @Test
    void anotherStoreCannotOpenTheSameDatabaseUntilItsOwnerCloses() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint point = stock(item, "Item", now, 42);
        try (HsqlStorageHistoryStore first = store(100);
            HsqlStorageHistoryStore second = new HsqlStorageHistoryStore(
                directory.resolve("database/./history"),
                7,
                100)) {
            first.append(grid, Collections.singletonList(point));
            assertThrows(IOException.class, () -> second.read(grid, item, now, 10));
            assertEquals(
                Collections.singletonList(point),
                first.read(grid, item, now, 10)
                    .points());
            first.close();
            assertEquals(
                Collections.singletonList(point),
                second.read(grid, item, now, 10)
                    .points());
        }
    }
}
