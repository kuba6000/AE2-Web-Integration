package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

class HsqlStorageHistoryStoreTest {

    @TempDir
    Path directory;

    @Test
    void reopenPreservesExactValuesWithOneCurrentDescriptionPerItem() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint first = stock(item, now, Long.MAX_VALUE);
        StorageHistoryPoint second = stock(item, now + 1, 11);
        try (HsqlStorageHistoryStore store = store(100)) {
            store.append(
                grid,
                Collections.singletonMap(item, description(item, "Before")),
                Collections.singletonList(first));
            store.append(
                grid,
                Collections.singletonMap(item, description(item, "After")),
                Collections.singletonList(second));
        }
        try (HsqlStorageHistoryStore store = store(100)) {
            StorageHistory history = store.read(grid, item, now, 10);
            assertEquals(Arrays.asList(first, second), history.points());
            assertEquals(Collections.singletonMap(item, description(item, "After")), history.descriptions());
            assertTrue(history.complete());
            assertFalse(history.truncated());
        }
    }

    @Test
    void sharedDescriptionSurvivesAnotherGridsPruningAndUnavailableBaseUpdates() throws Exception {
        StableKey firstGrid = StableKey.random();
        StableKey secondGrid = StableKey.random();
        StableKey item = StableKey.random();
        StableKey other = StableKey.random();
        long now = System.currentTimeMillis();
        ResourceDescriptor updated = new ResourceDescriptor(
            "test",
            "item",
            2,
            3,
            "Renamed",
            ResourceType.ITEM,
            item,
            null);
        StorageHistoryPoint retained = stock(item, now, 42);
        try (HsqlStorageHistoryStore store = store(1)) {
            append(store, firstGrid, Collections.singletonList(retained));
            store.append(secondGrid, Collections.singletonMap(item, updated), Collections.singletonList(retained));
            append(store, firstGrid, Collections.singletonList(stock(other, now + 1, 5)));
            StorageHistory remaining = store.read(secondGrid, item, now, 10);
            assertEquals(Collections.singletonList(retained), remaining.points());
            assertEquals(Collections.singletonMap(item, description(item, "Renamed")), remaining.descriptions());
        }
        try (HsqlStorageHistoryStore store = store(1)) {
            assertEquals(
                description(item, "Renamed"),
                store.read(secondGrid, item, now, 10)
                    .descriptions()
                    .get(item));
        }
    }

    @Test
    void rejectedAppendRollsBackDescriptionsAndNumericPointsTogether() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        StableKey missing = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint first = stock(item, now, 42);
        try (HsqlStorageHistoryStore store = store(100)) {
            append(store, grid, Collections.singletonList(first));
            assertThrows(
                IOException.class,
                () -> store.append(
                    grid,
                    Collections.singletonMap(item, description(item, "Uncommitted")),
                    Arrays.asList(stock(item, now + 1, 43), stock(missing, now + 2, 1))));
            StorageHistory unchanged = store.read(grid, item, now, 10);
            assertEquals(Collections.singletonList(first), unchanged.points());
            assertEquals(Collections.singletonMap(item, description(item, "Item")), unchanged.descriptions());
            store.append(grid, Collections.emptyMap(), Collections.singletonList(stock(item, now + 3, 44)));
            assertEquals(
                2,
                store.read(grid, item, now, 10)
                    .points()
                    .size());
        }
    }

    @Test
    void descriptionOnlyRefreshUpdatesKnownResourcesWithoutAdmittingUnobservedOnes() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        StableKey unobserved = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint first = stock(item, now, 42);
        try (HsqlStorageHistoryStore store = store(100)) {
            append(store, grid, Collections.singletonList(first));
            Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
            descriptions.put(item, description(item, "Renamed"));
            descriptions.put(unobserved, description(unobserved, "Unobserved"));
            store.append(grid, descriptions, Collections.emptyList());
            StorageHistory current = store.read(grid, item, now, 10);
            assertEquals(Collections.singletonList(first), current.points());
            assertEquals(Collections.singletonMap(item, description(item, "Renamed")), current.descriptions());
            assertThrows(
                IOException.class,
                () -> store
                    .append(grid, Collections.emptyMap(), Collections.singletonList(stock(unobserved, now + 1, 1))));
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
        StorageHistoryPoint first = stock(item, now, 1);
        StorageHistoryPoint second = stock(item, now + 1, 2);
        StorageHistoryPoint third = stock(item, now + 2, 3);
        StorageHistoryPoint fourth = stock(item, now + 3, 4);
        try (HsqlStorageHistoryStore store = store(2)) {
            append(store, grid, Arrays.asList(first, second, third));
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
            append(store, grid, Collections.singletonList(fourth));
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
        StorageHistoryPoint expired = stock(item, now - TimeUnit.DAYS.toMillis(8), 1);
        StorageHistoryPoint recent = stock(item, now, 2);
        try (HsqlStorageHistoryStore store = store(100)) {
            append(store, firstGrid, Arrays.asList(expired, recent));
            append(store, otherGrid, Collections.singletonList(recent));
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

    private static StorageHistoryPoint stock(StableKey item, long time, long amount) {
        return new StorageHistoryPoint(time, 7, StorageHistoryPoint.Kind.STOCK, item, amount, true, 0, 0, true);
    }

    private static ResourceDescriptor description(StableKey item, String name) {
        return new ResourceDescriptor("test", "item", 2, 3, name, ResourceType.ITEM, item, item);
    }

    private static void append(HsqlStorageHistoryStore store, StableKey grid,
        java.util.List<StorageHistoryPoint> points) throws IOException {
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        for (StorageHistoryPoint point : points) {
            if (point.itemKey() != null) descriptions.put(point.itemKey(), description(point.itemKey(), "Item"));
        }
        store.append(grid, descriptions, points);
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
            0,
            false,
            0,
            0,
            false);
        StorageHistoryPoint flow = new StorageHistoryPoint(
            now + 2,
            1,
            StorageHistoryPoint.Kind.FLOW,
            item,
            0,
            false,
            Long.MAX_VALUE,
            Long.MAX_VALUE - 1,
            true);
        StorageHistoryPoint observation = new StorageHistoryPoint(
            now + 3,
            1,
            StorageHistoryPoint.Kind.FLOW_OBSERVATION,
            null,
            0,
            false,
            0,
            0,
            true);
        try (HsqlStorageHistoryStore store = store(100)) {
            append(
                store,
                grid,
                Arrays.asList(gap, stock(item, now + 1, 1), stock(other, now + 1, 99), flow, observation));
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
        StorageHistoryPoint old = stock(item, now - TimeUnit.DAYS.toMillis(2), 1);
        StorageHistoryPoint recent = stock(item, now, 2);
        try (HsqlStorageHistoryStore store = store(100)) {
            append(store, grid, Arrays.asList(old, recent));
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
        StorageHistoryPoint anchor = stock(item, now - TimeUnit.DAYS.toMillis(8), 42);
        StorageHistoryPoint marker = new StorageHistoryPoint(
            now,
            7,
            StorageHistoryPoint.Kind.FLOW_OBSERVATION,
            null,
            0,
            false,
            0,
            0,
            true);
        try (HsqlStorageHistoryStore store = store(100)) {
            append(store, grid, Arrays.asList(anchor, marker, marker, marker));
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
        StorageHistoryPoint old = stock(item, now - TimeUnit.DAYS.toMillis(8), 42);
        StorageHistoryPoint marker = new StorageHistoryPoint(
            now,
            7,
            StorageHistoryPoint.Kind.OBSERVATION,
            null,
            0,
            false,
            0,
            0,
            true);
        try (HsqlStorageHistoryStore store = store(2)) {
            append(store, grid, Arrays.asList(old, marker, marker));
            StorageHistory lost = store.read(grid, item, 0, 10);
            assertNull(lost.baseline());
            assertFalse(lost.complete());
            StorageHistoryPoint fresh = stock(item, now + 1, 45);
            append(store, grid, Collections.singletonList(fresh));
            StorageHistory restored = store.read(grid, item, now + 2, 10);
            assertEquals(fresh, restored.baseline());
        }
    }

    @Test
    void loweredRowBudgetAppliesOnReopenWithoutAnotherWrite() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        StorageHistoryPoint first = stock(item, now, 1);
        StorageHistoryPoint second = stock(item, now + 1, 2);
        StorageHistoryPoint third = stock(item, now + 2, 3);
        try (HsqlStorageHistoryStore store = store(100)) {
            append(store, grid, Arrays.asList(first, second, third));
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
        StorageHistoryPoint point = stock(item, now, 42);
        try (HsqlStorageHistoryStore first = store(100);
            HsqlStorageHistoryStore second = new HsqlStorageHistoryStore(
                directory.resolve("database/./history"),
                7,
                100)) {
            append(first, grid, Collections.singletonList(point));
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
