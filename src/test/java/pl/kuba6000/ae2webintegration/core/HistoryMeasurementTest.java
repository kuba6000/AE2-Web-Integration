package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.TestGridFixtures.TestGrid;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint.Kind;

class HistoryMeasurementTest extends GridTestScope {

    @Test
    void logicalBudgetPrunesChildrenAndTheirUnusedHeadersAndFailedFlowLeavesNeither() throws Exception {
        ConfigSettings.ResourceHistory policy = new ConfigSettings.ResourceHistory();
        policy.historyMaxRowsPerGrid = 3;
        WorldDatabase database = new WorldDatabase(
            gridSave.toPath()
                .resolve("small-budget"),
            policy);
        GridIdentityRegistry registry = new GridIdentityRegistry();
        registry.initialize(
            database,
            gridSave.toPath()
                .resolve("absent.json"));
        TestGrid live = new TestGrid(88404, false, AEControllerState.CONTROLLER_ONLINE);
        registry.controllerValidated(live);
        StableKey grid = registry.getKey(live);
        assertNotNull(grid);
        StableKey item = TestGridFixtures.key(76);
        ResourceDescriptor description = new ResourceDescriptor(
            "test",
            "item",
            0,
            0,
            "Item",
            ResourceType.ITEM,
            item,
            null);
        long now = System.currentTimeMillis();
        try (HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(database)) {
            history.append(
                grid,
                Collections.singletonMap(item, description),
                Arrays.asList(
                    new StorageHistoryPoint(now, 1, Kind.STOCK, item, 42, 0, 0, 0, 0, null),
                    new StorageHistoryPoint(now, 1, Kind.OBSERVATION, null, 0, 0, 0, 0, 0, null),
                    new StorageHistoryPoint(now, 1, Kind.FLOW, item, 0, 5, 2, 0, 0, now - 1000),
                    new StorageHistoryPoint(now, 1, Kind.FLOW_OBSERVATION, null, 0, 0, 0, 0, 0, now - 1000)));
            assertEquals(
                3,
                history.read(grid, null, now, 100)
                    .points()
                    .size());
            List<StorageHistoryPoint> retained = Arrays.asList(
                new StorageHistoryPoint(now + 1, 1, Kind.GAP, null, 0, 0, 0, 0, 0, null),
                new StorageHistoryPoint(now + 1, 1, Kind.STOCK, item, 43, 0, 0, 0, 0, null),
                new StorageHistoryPoint(now + 2, 1, Kind.STOCK, item, 44, 0, 0, 0, 0, null));
            history.append(grid, Collections.emptyMap(), retained);
            assertEquals(
                retained,
                history.read(grid, null, now, 100)
                    .points());
            assertTrue(
                history.read(grid, null, now, 100)
                    .truncated());
            assertThrows(
                IOException.class,
                () -> history.append(
                    grid,
                    Collections.emptyMap(),
                    Arrays.asList(
                        new StorageHistoryPoint(now + 3, 1, Kind.STOCK, item, 45, 0, 0, 0, 0, null),
                        new StorageHistoryPoint(now + 3, 1, Kind.FLOW, item, 0, 1, 0, 0, 0, null))));
            history.append(grid, Collections.emptyMap(), Collections.emptyList());
            assertEquals(
                retained,
                history.read(grid, null, now, 100)
                    .points());
            database.read(db -> {
                try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery("""
                    SELECT (SELECT row_count FROM grids), (SELECT COUNT(*) FROM history_measurements),
                        (SELECT COUNT(*) FROM history_stock) + (SELECT COUNT(*) FROM history_flow)
                            + (SELECT COUNT(*) FROM history_coverage)
                    FROM (VALUES (0))
                    """)) {
                    assertTrue(rows.next());
                    assertEquals(3, rows.getInt(1));
                    assertEquals(3, rows.getInt(2));
                    assertEquals(3, rows.getInt(3));
                }
                return null;
            });
        }
    }

    @Test
    void stockFlowAndCoverageShareMetadataWhileHeterogeneousAppendPreservesEventOrder() throws Exception {
        StableKey grid = TestGridFixtures.resolvedKey(new TestGrid(88403, false, AEControllerState.CONTROLLER_ONLINE));
        StableKey item = TestGridFixtures.key(75);
        ResourceDescriptor description = new ResourceDescriptor(
            "test",
            "item",
            0,
            0,
            "Item",
            ResourceType.ITEM,
            item,
            null);
        WorldDatabase database = worlds.database(TestGridFixtures.Worlds.source(gridSave));
        long now = System.currentTimeMillis();
        List<StorageHistoryPoint> shared = Arrays.asList(
            new StorageHistoryPoint(now, 1, Kind.STOCK, item, 42, 0, 0, 0, 0, null),
            new StorageHistoryPoint(now, 1, Kind.OBSERVATION, null, 0, 0, 0, 0, 0, null),
            new StorageHistoryPoint(now, 1, Kind.FLOW, item, 0, 5, 2, 0, 0, now - 1000),
            new StorageHistoryPoint(now, 1, Kind.FLOW_OBSERVATION, null, 0, 0, 0, 0, 0, now - 1000));
        try (HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(database)) {
            history.append(grid, Collections.singletonMap(item, description), shared);
            assertEquals(
                shared,
                history.read(grid, null, now, 100)
                    .points());
            database.read(db -> {
                try (Statement sql = db.createStatement();
                    ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM history_measurements")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
                return null;
            });
            List<StorageHistoryPoint> heterogeneous = Arrays.asList(
                new StorageHistoryPoint(now + 1, 1, Kind.STOCK, item, 43, 0, 0, 0, 0, null),
                new StorageHistoryPoint(now + 1, 1, Kind.GAP, null, 0, 0, 0, 0, 0, null),
                new StorageHistoryPoint(now + 1, 1, Kind.FLOW, item, 0, 6, 3, 0, 0, now),
                new StorageHistoryPoint(now + 1, 1, Kind.FLOW_OBSERVATION, null, 0, 0, 0, 0, 0, now - 500),
                new StorageHistoryPoint(now + 1, 2, Kind.OBSERVATION, null, 0, 0, 0, 0, 0, null),
                new StorageHistoryPoint(now + 2, 2, Kind.FLOW_OBSERVATION, null, 0, 0, 0, 0, 0, now + 1));
            history.append(grid, Collections.emptyMap(), heterogeneous);
            assertEquals(
                heterogeneous,
                history.read(grid, null, now + 1, 100)
                    .points());
            assertFalse(
                history.read(grid, item, now + 1, 100)
                    .complete());
            database.read(db -> {
                try (Statement sql = db.createStatement();
                    ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM history_measurements")) {
                    assertTrue(rows.next());
                    assertEquals(7, rows.getInt(1));
                }
                return null;
            });
        }
        worlds.close(TestGridFixtures.Worlds.source(gridSave));
        try (HsqlStorageHistoryStore reopened = new HsqlStorageHistoryStore(
            worlds.database(TestGridFixtures.Worlds.source(gridSave)))) {
            assertEquals(
                shared,
                reopened.read(grid, null, now, 100)
                    .points()
                    .subList(0, shared.size()));
        }
    }
}
