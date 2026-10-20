package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.TestGridFixtures.TestGrid;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.HomeConfig;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Pin;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Placement;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint.Kind;

class ResourcePinStoreTest extends GridTestScope {

    @Test
    void unchangedDescriptionsDoNotRewriteCatalogueAndMissingBaseKeepsKnownIcon() throws Exception {
        StableKey grid = TestGridFixtures.resolvedKey(new TestGrid(88401, false, AEControllerState.CONTROLLER_ONLINE));
        StableKey item = TestGridFixtures.key(71);
        StableKey base = TestGridFixtures.key(72);
        WorldDatabase database = worlds.database(TestGridFixtures.Worlds.source(gridSave));
        ResourcePinStore pins = new ResourcePinStore(database);
        ResourceDescriptor unknown = new ResourceDescriptor(
            null,
            null,
            0,
            0,
            "Unknown",
            ResourceType.OTHER,
            item,
            null);
        assertTrue(pins.add(WebPrincipal.admin(), grid, item, unknown, Placement.HOME));
        database.write(db -> {
            try (Statement sql = db.createStatement()) {
                sql.execute("""
                    CREATE TRIGGER reject_resource_update BEFORE UPDATE ON history_resources
                    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected catalogue update'
                    """);
            }
            return null;
        });
        assertTrue(pins.add(WebPrincipal.admin(), grid, item, unknown, Placement.TERMINAL));
        assertEquals(
            unknown,
            pins.list(WebPrincipal.admin())
                .get(0)
                .resource());
        database.write(db -> {
            try (Statement sql = db.createStatement()) {
                sql.execute("DROP TRIGGER reject_resource_update");
            }
            return null;
        });
        ResourceDescriptor known = new ResourceDescriptor(
            "test",
            "known",
            2,
            3,
            "Known",
            ResourceType.ITEM,
            item,
            base);
        assertTrue(pins.add(WebPrincipal.admin(), grid, item, known, Placement.HOME));
        assertEquals(
            known,
            pins.list(WebPrincipal.admin())
                .get(0)
                .resource());
        database.write(db -> {
            try (Statement sql = db.createStatement()) {
                sql.execute("""
                    CREATE TRIGGER reject_resource_update BEFORE UPDATE ON history_resources
                    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected catalogue update'
                    """);
            }
            return null;
        });
        ResourceDescriptor withoutBase = new ResourceDescriptor(
            "test",
            "known",
            2,
            3,
            "Known",
            ResourceType.ITEM,
            item,
            null);
        assertTrue(pins.add(WebPrincipal.admin(), grid, item, withoutBase, Placement.HOME));
        try (HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(database)) {
            history.append(grid, Collections.singletonMap(item, withoutBase), Collections.emptyList());
            assertEquals(
                known,
                pins.list(WebPrincipal.admin())
                    .get(0)
                    .resource());
        }
    }

    @Test
    void catalogueRefreshesEachMetadataFieldIncludingNullableRegistryValues() throws Exception {
        StableKey grid = TestGridFixtures.resolvedKey(new TestGrid(88402, false, AEControllerState.CONTROLLER_ONLINE));
        StableKey item = TestGridFixtures.key(73);
        StableKey base = TestGridFixtures.key(74);
        ResourcePinStore pins = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        ResourceDescriptor[] revisions = {
            new ResourceDescriptor(null, null, 0, 0, "Unknown", ResourceType.OTHER, item, null),
            new ResourceDescriptor("test", null, 0, 0, "Unknown", ResourceType.OTHER, item, null),
            new ResourceDescriptor("test", "known", 0, 0, "Unknown", ResourceType.OTHER, item, null),
            new ResourceDescriptor("test", "known", 2, 0, "Unknown", ResourceType.OTHER, item, null),
            new ResourceDescriptor("test", "known", 2, 3, "Unknown", ResourceType.OTHER, item, null),
            new ResourceDescriptor("test", "known", 2, 3, "Known", ResourceType.OTHER, item, null),
            new ResourceDescriptor("test", "known", 2, 3, "Known", ResourceType.ITEM, item, null),
            new ResourceDescriptor("test", "known", 2, 3, "Known", ResourceType.ITEM, item, base),
            new ResourceDescriptor(null, "known", 2, 3, "Known", ResourceType.ITEM, item, base),
            new ResourceDescriptor(null, null, 2, 3, "Known", ResourceType.ITEM, item, base) };
        for (ResourceDescriptor revision : revisions) {
            assertTrue(pins.add(WebPrincipal.admin(), grid, item, revision, Placement.HOME));
            assertEquals(
                revision,
                pins.list(WebPrincipal.admin())
                    .get(0)
                    .resource());
        }
        worlds.close(TestGridFixtures.Worlds.source(gridSave));
        ResourcePinStore reopened = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        assertEquals(
            revisions[revisions.length - 1],
            reopened.list(WebPrincipal.admin())
                .get(0)
                .resource());
    }

    @Test
    void capturedHistoryPersistsAnIdentityRetiredBeforeItsFirstFlush() throws Exception {
        TestGrid grid = new TestGrid(88400, false, AEControllerState.CONTROLLER_ONLINE);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        GridSettingsData retiredSettings = data.getSettings();
        retiredSettings.setName("Retired");
        retiredSettings.setTracked(true);
        retiredSettings.setTrackMachineCrafting(true);
        retiredSettings.setStorageTracked(true);
        retiredSettings.setFlowTracked(true);
        retiredSettings.setStockSampleIntervalSeconds(600);
        retiredSettings.setFlowFlushIntervalSeconds(120);
        retiredSettings.setHistoryRetentionDays(14);
        CoreEngine.GRID_IDENTITIES.controllerRemoved(grid.position());
        assertNull(CoreEngine.GRID_IDENTITIES.getPersistentData(key));
        // A stale settings handle must not change the final policy of a retired identity.
        retiredSettings.setHistoryRetentionDays(1);
        retiredSettings.setName("Stale");
        retiredSettings.setTracked(false);
        retiredSettings.setTrackMachineCrafting(false);
        retiredSettings.setStorageTracked(false);
        retiredSettings.setFlowTracked(false);
        retiredSettings.setStockSampleIntervalSeconds(60);
        retiredSettings.setFlowFlushIntervalSeconds(60);
        StableKey item = TestGridFixtures.key(70);
        long observed = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
        WorldDatabase database = worlds.database(TestGridFixtures.Worlds.source(gridSave));
        try (HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(database)) {
            history.append(
                key,
                Collections.singletonMap(item, description(item, "Captured")),
                Collections.singletonList(
                    new StorageHistoryPoint(observed, 1, Kind.FLOW, item, 0, 5, 2, 0, 0, observed - 1000)));
            assertEquals(
                1,
                history.read(key, item, 0, 100)
                    .points()
                    .size());
            database.read(db -> {
                try (PreparedStatement query = db.prepareStatement("SELECT * FROM grids WHERE grid_key = ?")) {
                    query.setString(1, key.toString());
                    try (ResultSet row = query.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals("Retired", row.getString("name"));
                        assertTrue(row.getBoolean("crafting_tracked"));
                        assertTrue(row.getBoolean("machine_crafting_tracked"));
                        assertTrue(row.getBoolean("stock_tracked"));
                        assertTrue(row.getBoolean("flow_tracked"));
                        assertEquals(600, row.getInt("stock_seconds"));
                        assertEquals(120, row.getInt("flow_seconds"));
                        assertEquals(14, row.getInt("retention_days"));
                    }
                }
                return null;
            });
        }
        worlds.close(TestGridFixtures.Worlds.source(gridSave));
        try (HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(
            worlds.database(TestGridFixtures.Worlds.source(gridSave)))) {
            assertEquals(
                1,
                history.read(key, item, 0, 100)
                    .points()
                    .size());
        }
    }

    @Test
    void terminalAndHomeOrdersRemainIndependentAcrossGridsAndConfigurationIsAtomic() throws Exception {
        StableKey firstGrid = TestGridFixtures
            .resolvedKey(new TestGrid(88392, false, AEControllerState.CONTROLLER_ONLINE));
        StableKey secondGrid = TestGridFixtures
            .resolvedKey(new TestGrid(88393, false, AEControllerState.CONTROLLER_ONLINE));
        StableKey first = TestGridFixtures.key(62);
        StableKey second = TestGridFixtures.key(63);
        StableKey third = TestGridFixtures.key(64);
        ResourcePinStore pins = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        WebPrincipal owner = WebPrincipal.admin();
        assertTrue(pins.add(owner, firstGrid, first, description(first, "First"), Placement.TERMINAL));
        assertTrue(pins.add(owner, firstGrid, second, description(second, "Second"), Placement.TERMINAL));
        assertTrue(pins.add(owner, secondGrid, third, description(third, "Third"), Placement.TERMINAL));
        assertTrue(pins.add(owner, firstGrid, second, null, Placement.HOME));
        assertTrue(pins.add(owner, firstGrid, first, null, Placement.HOME));
        assertTrue(pins.add(owner, secondGrid, third, null, Placement.HOME));
        List<Pin> records = pins.list(owner);
        Pin firstPin = records.stream()
            .filter(
                pin -> pin.itemKey()
                    .equals(first))
            .findFirst()
            .get();
        Pin secondPin = records.stream()
            .filter(
                pin -> pin.itemKey()
                    .equals(second))
            .findFirst()
            .get();
        Pin thirdPin = records.stream()
            .filter(
                pin -> pin.itemKey()
                    .equals(third))
            .findFirst()
            .get();
        assertEquals(0, thirdPin.terminalOrder());
        assertEquals(2, thirdPin.homeOrder());
        assertTrue(
            pins.update(
                owner,
                secondPin.pinKey(),
                null,
                null,
                new ResourcePinStore.Move(Placement.TERMINAL, ResourcePinStore.Direction.EARLIER),
                null));
        assertTrue(
            pins.update(
                owner,
                thirdPin.pinKey(),
                null,
                null,
                new ResourcePinStore.Move(Placement.HOME, ResourcePinStore.Direction.EARLIER),
                null));
        HomeConfig config = new HomeConfig(false, true, true, false, false, false, true, 604800000, false, 900000);
        assertTrue(pins.update(owner, firstPin.pinKey(), null, null, null, config));
        List<Pin> changed = pins.list(owner);
        for (Pin pin : changed) {
            if (pin.itemKey()
                .equals(first)) {
                assertEquals(1, pin.terminalOrder());
                assertEquals(2, pin.homeOrder());
                assertEquals(config, pin.homeConfig());
            }
            if (pin.itemKey()
                .equals(second)) {
                assertEquals(0, pin.terminalOrder());
                assertEquals(0, pin.homeOrder());
            }
            if (pin.itemKey()
                .equals(third)) {
                assertEquals(0, pin.terminalOrder());
                assertEquals(1, pin.homeOrder());
            }
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> pins.update(
                owner,
                firstPin.pinKey(),
                false,
                null,
                null,
                new HomeConfig(true, true, true, true, true, true, true, 0, true, 86400000)));
        assertEquals(changed, pins.list(owner));
        assertThrows(
            IllegalArgumentException.class,
            () -> pins.update(
                owner,
                firstPin.pinKey(),
                null,
                false,
                new ResourcePinStore.Move(Placement.HOME, ResourcePinStore.Direction.EARLIER),
                null));
        assertEquals(changed, pins.list(owner));
        assertThrows(
            IllegalArgumentException.class,
            () -> pins.update(
                owner,
                firstPin.pinKey(),
                false,
                null,
                null,
                new HomeConfig(true, true, true, true, true, true, true, 86400000, true, 0)));
        assertEquals(changed, pins.list(owner));
        worlds.close(TestGridFixtures.Worlds.source(gridSave));
        ResourcePinStore reopened = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        assertEquals(changed, reopened.list(owner));
        reopened.remove(owner, secondPin.pinKey());
        for (Pin pin : reopened.list(owner)) {
            assertEquals(0, pin.terminalOrder());
            assertEquals(
                pin.itemKey()
                    .equals(third) ? 0 : 1,
                pin.homeOrder());
        }
    }

    @Test
    void pinAndDescriptorRollbackTogetherWhenStorageRejectsCreation() throws Exception {
        StableKey key = TestGridFixtures.resolvedKey(new TestGrid(88394, false, AEControllerState.CONTROLLER_ONLINE));
        StableKey item = TestGridFixtures.key(65);
        WorldDatabase database = worlds.database(TestGridFixtures.Worlds.source(gridSave));
        ResourcePinStore pins = new ResourcePinStore(database);
        WebPrincipal player = TestGridFixtures.principal(4);
        assertTrue(pins.add(player, key, item, description(item, "Before"), Placement.HOME));
        database.write(db -> {
            try (Statement sql = db.createStatement()) {
                sql.execute("ALTER TABLE resource_pins ADD CONSTRAINT temporary_pin_failure CHECK (owner <> 'admin')");
            }
            return null;
        });
        assertThrows(
            IOException.class,
            () -> pins.add(WebPrincipal.admin(), key, item, description(item, "After"), Placement.HOME));
        assertTrue(
            pins.list(WebPrincipal.admin())
                .isEmpty());
        assertEquals(
            "Before",
            pins.list(player)
                .get(0)
                .resource()
                .displayName());
        database.write(db -> {
            try (Statement sql = db.createStatement()) {
                sql.execute("ALTER TABLE resource_pins DROP CONSTRAINT temporary_pin_failure");
            }
            return null;
        });
        assertFalse(
            pins.add(WebPrincipal.admin(), key, item, null, Placement.HOME),
            "Another owner's pin is not membership authority");
        assertTrue(pins.add(WebPrincipal.admin(), key, item, description(item, "After"), Placement.HOME));
        assertEquals(
            "After",
            pins.list(player)
                .get(0)
                .resource()
                .displayName());
    }

    @Test
    void pinnedDescriptorSurvivesLastHistoryPruneAndLastUnpinReclaimsIt() throws Exception {
        StableKey key = TestGridFixtures.resolvedKey(new TestGrid(88395, false, AEControllerState.CONTROLLER_ONLINE));
        StableKey item = TestGridFixtures.key(66);
        File source = TestGridFixtures.Worlds.source(gridSave);
        WorldDatabase database = worlds.database(source);
        ResourcePinStore pins = new ResourcePinStore(database);
        assertTrue(pins.add(WebPrincipal.admin(), key, item, description(item, "Pinned"), Placement.HOME));
        long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(9);
        try (HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(database)) {
            history.append(
                key,
                Collections.emptyMap(),
                Collections
                    .singletonList(new StorageHistoryPoint(old, 1, Kind.FLOW, item, 0, 5, 2, 0, 0, old - 60000)));
        }
        worlds.close(source);
        WorldDatabase reopened = worlds.database(source);
        ResourcePinStore restored = new ResourcePinStore(reopened);
        try (HsqlStorageHistoryStore history = new HsqlStorageHistoryStore(reopened)) {
            assertTrue(
                history.read(key, item, 0, 100)
                    .points()
                    .isEmpty());
            assertEquals(
                "Pinned",
                restored.list(WebPrincipal.admin())
                    .get(0)
                    .resource()
                    .displayName());
            assertTrue(restored.add(WebPrincipal.admin(), key, item, null, Placement.TERMINAL));
            assertFalse(restored.add(TestGridFixtures.principal(4), key, item, null, Placement.HOME));
            restored.remove(
                WebPrincipal.admin(),
                restored.list(WebPrincipal.admin())
                    .get(0)
                    .pinKey());
            assertThrows(
                IOException.class,
                () -> history.append(
                    key,
                    Collections.emptyMap(),
                    Collections.singletonList(
                        new StorageHistoryPoint(
                            System.currentTimeMillis(),
                            2,
                            Kind.STOCK,
                            item,
                            1,
                            0,
                            0,
                            0,
                            0,
                            null))));
        }
    }

    private static ResourceDescriptor description(StableKey item, String name) {
        return new ResourceDescriptor("test", "item", 0, 0, name, ResourceType.ITEM, item, item);
    }

    @Test
    void placementsAreIndependentAndAdministratorOwnershipSurvivesRestart() throws Exception {
        TestGrid grid = new TestGrid(88391, false, AEControllerState.CONTROLLER_ONLINE);
        StableKey key = TestGridFixtures.resolvedKey(grid);
        StableKey item = TestGridFixtures.key(61);
        ResourceDescriptor descriptor = new ResourceDescriptor(
            "test",
            "iron",
            0,
            0,
            "Iron",
            ResourceType.ITEM,
            item,
            item);
        ResourcePinStore pins = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        assertTrue(pins.add(WebPrincipal.admin(), key, item, descriptor, Placement.TERMINAL));
        assertTrue(pins.add(WebPrincipal.localhost(), key, item, null, Placement.HOME));
        assertTrue(pins.add(WebPrincipal.admin(), key, item, null, Placement.TERMINAL));
        List<Pin> selected = pins.list(WebPrincipal.admin());
        assertEquals(1, selected.size());
        assertTrue(
            selected.get(0)
                .homeConfig()
                .showRecentFlow());
        assertEquals(
            86400000,
            selected.get(0)
                .homeConfig()
                .flowRangeMillis());
        assertEquals(
            0,
            selected.get(0)
                .terminalOrder());
        assertEquals(
            0,
            selected.get(0)
                .homeOrder());
        assertTrue(
            pins.list(TestGridFixtures.principal(4))
                .isEmpty());
        StableKey pinKey = selected.get(0)
            .pinKey();
        assertTrue(pins.update(WebPrincipal.admin(), pinKey, false, null, null, null));
        assertNull(
            pins.list(WebPrincipal.admin())
                .get(0)
                .terminalOrder());
        assertEquals(
            0,
            pins.list(WebPrincipal.admin())
                .get(0)
                .homeOrder());
        worlds.close(TestGridFixtures.Worlds.source(gridSave));
        pins = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        assertEquals(
            pinKey,
            pins.list(WebPrincipal.localhost())
                .get(0)
                .pinKey());
        assertFalse(pins.update(TestGridFixtures.principal(4), pinKey, null, false, null, null));
        pins.remove(TestGridFixtures.principal(4), pinKey);
        assertEquals(
            1,
            pins.list(WebPrincipal.admin())
                .size());
        pins.remove(WebPrincipal.localhost(), pinKey);
        assertTrue(
            pins.list(WebPrincipal.admin())
                .isEmpty());
        assertFalse(pins.add(WebPrincipal.admin(), key, item, null, Placement.TERMINAL));
    }
}
