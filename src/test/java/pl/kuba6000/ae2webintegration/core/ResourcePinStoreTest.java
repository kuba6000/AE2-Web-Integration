package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Placement;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

class ResourcePinStoreTest extends GridTestScope {

    @Test
    void terminalAndHomeOrdersRemainIndependentAcrossGridsAndConfigurationIsAtomic() throws Exception {
        var firstGrid = TestGridFixtures
            .resolvedKey(new TestGridFixtures.TestGrid(88392, false, AEControllerState.CONTROLLER_ONLINE));
        var secondGrid = TestGridFixtures
            .resolvedKey(new TestGridFixtures.TestGrid(88393, false, AEControllerState.CONTROLLER_ONLINE));
        var first = TestGridFixtures.key(62);
        var second = TestGridFixtures.key(63);
        var third = TestGridFixtures.key(64);
        var pins = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        var owner = WebPrincipal.admin();
        assertTrue(pins.add(owner, firstGrid, first, description(first, "First"), Placement.TERMINAL));
        assertTrue(pins.add(owner, firstGrid, second, description(second, "Second"), Placement.TERMINAL));
        assertTrue(pins.add(owner, secondGrid, third, description(third, "Third"), Placement.TERMINAL));
        assertTrue(pins.add(owner, firstGrid, second, null, Placement.HOME));
        assertTrue(pins.add(owner, firstGrid, first, null, Placement.HOME));
        assertTrue(pins.add(owner, secondGrid, third, null, Placement.HOME));
        var records = pins.list(owner);
        var firstPin = records.stream()
            .filter(
                pin -> pin.itemKey()
                    .equals(first))
            .findFirst()
            .get();
        var secondPin = records.stream()
            .filter(
                pin -> pin.itemKey()
                    .equals(second))
            .findFirst()
            .get();
        var thirdPin = records.stream()
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
        var config = new ResourcePinStore.HomeConfig(
            false,
            true,
            true,
            false,
            false,
            false,
            true,
            604800000,
            false,
            900000);
        assertTrue(pins.update(owner, firstPin.pinKey(), null, null, null, config));
        var changed = pins.list(owner);
        for (var pin : changed) {
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
                new ResourcePinStore.HomeConfig(true, true, true, true, true, true, true, 0, true, 86400000)));
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
                new ResourcePinStore.HomeConfig(true, true, true, true, true, true, true, 86400000, true, 0)));
        assertEquals(changed, pins.list(owner));
        worlds.close(TestGridFixtures.Worlds.source(gridSave));
        var reopened = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        assertEquals(changed, reopened.list(owner));
        reopened.remove(owner, secondPin.pinKey());
        for (var pin : reopened.list(owner)) {
            assertEquals(0, pin.terminalOrder());
            assertEquals(
                pin.itemKey()
                    .equals(third) ? 0 : 1,
                pin.homeOrder());
        }
    }

    @Test
    void pinAndDescriptorRollbackTogetherWhenStorageRejectsCreation() throws Exception {
        var key = TestGridFixtures
            .resolvedKey(new TestGridFixtures.TestGrid(88394, false, AEControllerState.CONTROLLER_ONLINE));
        var item = TestGridFixtures.key(65);
        var database = worlds.database(TestGridFixtures.Worlds.source(gridSave));
        var pins = new ResourcePinStore(database);
        var player = TestGridFixtures.principal(4);
        assertTrue(pins.add(player, key, item, description(item, "Before"), Placement.HOME));
        database.write(db -> {
            try (var sql = db.createStatement()) {
                sql.execute("ALTER TABLE resource_pins ADD CONSTRAINT temporary_pin_failure CHECK (owner <> 'admin')");
            }
            return null;
        });
        assertThrows(
            java.io.IOException.class,
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
            try (var sql = db.createStatement()) {
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
        var key = TestGridFixtures
            .resolvedKey(new TestGridFixtures.TestGrid(88395, false, AEControllerState.CONTROLLER_ONLINE));
        var item = TestGridFixtures.key(66);
        var source = TestGridFixtures.Worlds.source(gridSave);
        var database = worlds.database(source);
        var pins = new ResourcePinStore(database);
        assertTrue(pins.add(WebPrincipal.admin(), key, item, description(item, "Pinned"), Placement.HOME));
        long old = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(9);
        try (var history = new pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore(database)) {
            history.append(
                key,
                java.util.Collections.emptyMap(),
                java.util.Collections.singletonList(
                    new pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint(
                        old,
                        1,
                        pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint.Kind.FLOW,
                        item,
                        0,
                        5,
                        2,
                        old - 60000)));
        }
        worlds.close(source);
        var reopened = worlds.database(source);
        var restored = new ResourcePinStore(reopened);
        try (var history = new pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore(reopened)) {
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
                java.io.IOException.class,
                () -> history.append(
                    key,
                    java.util.Collections.emptyMap(),
                    java.util.Collections.singletonList(
                        new pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint(
                            System.currentTimeMillis(),
                            2,
                            pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint.Kind.STOCK,
                            item,
                            1,
                            0,
                            0,
                            null))));
        }
    }

    private static ResourceDescriptor description(pl.kuba6000.ae2webintegration.core.identity.StableKey item,
        String name) {
        return new ResourceDescriptor("test", "item", 0, 0, name, ResourceType.ITEM, item, item);
    }

    @Test
    void placementsAreIndependentAndAdministratorOwnershipSurvivesRestart() throws Exception {
        var grid = new TestGridFixtures.TestGrid(88391, false, AEControllerState.CONTROLLER_ONLINE);
        var key = TestGridFixtures.resolvedKey(grid);
        var item = TestGridFixtures.key(61);
        var descriptor = new ResourceDescriptor("test", "iron", 0, 0, "Iron", ResourceType.ITEM, item, item);
        var pins = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        assertTrue(pins.add(WebPrincipal.admin(), key, item, descriptor, Placement.TERMINAL));
        assertTrue(pins.add(WebPrincipal.localhost(), key, item, null, Placement.HOME));
        assertTrue(pins.add(WebPrincipal.admin(), key, item, null, Placement.TERMINAL));
        var selected = pins.list(WebPrincipal.admin());
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
        var pinKey = selected.get(0)
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
