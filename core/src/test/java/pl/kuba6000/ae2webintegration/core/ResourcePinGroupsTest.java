package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.TestGridFixtures.TestGrid;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Direction;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.HomeConfig;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.HomeGroup;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.HomeGroupSelection;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Move;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Pin;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Placement;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

class ResourcePinGroupsTest extends GridTestScope {

    @Test
    void homeGroupsBelongToTheAccountAndSurviveReopening() throws Exception {
        ResourcePinStore store = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        HomeGroup group = store.createGroup(WebPrincipal.admin(), "  Production  ");
        assertEquals("Production", group.name());
        assertEquals(0, group.ordinal());
        assertEquals(
            Collections.singletonList(group),
            store.snapshot(WebPrincipal.localhost())
                .groups());
        assertTrue(
            store.snapshot(TestGridFixtures.principal(4))
                .groups()
                .isEmpty());
        worlds.close(TestGridFixtures.Worlds.source(gridSave));
        ResourcePinStore reopened = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        assertEquals(
            Collections.singletonList(group),
            reopened.snapshot(WebPrincipal.admin())
                .groups());
    }

    @Test
    void groupEditingValidatesNamesAndKeepsAnIndependentCompactAccountOrder() throws Exception {
        ResourcePinStore store = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        WebPrincipal owner = WebPrincipal.admin();
        WebPrincipal other = TestGridFixtures.principal(4);
        HomeGroup first = store.createGroup(owner, "Production");
        HomeGroup second = store.createGroup(owner, "Storage");
        HomeGroup third = store.createGroup(owner, "Other");
        store.createGroup(other, "Production");
        assertThrows(IllegalArgumentException.class, () -> store.createGroup(owner, " Production "));
        assertThrows(IllegalArgumentException.class, () -> store.createGroup(owner, "   "));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.createGroup(owner, String.join("", Collections.nCopies(129, "x"))));
        assertTrue(store.updateGroup(owner, third.groupKey(), "  Materials  ", Direction.EARLIER));
        assertEquals(Arrays.asList(first.groupKey(), third.groupKey(), second.groupKey()), groupKeys(store, owner));
        assertEquals(
            "Materials",
            store.snapshot(owner)
                .groups()
                .get(1)
                .name());
        assertThrows(IllegalArgumentException.class, () -> store.updateGroup(owner, first.groupKey(), "Storage", null));
        assertFalse(store.updateGroup(other, first.groupKey(), "Stolen", Direction.LATER));
        store.removeGroup(other, second.groupKey());
        assertEquals(
            3,
            store.snapshot(owner)
                .groups()
                .size());
        store.removeGroup(owner, first.groupKey());
        store.removeGroup(owner, first.groupKey());
        assertEquals(Arrays.asList(third.groupKey(), second.groupKey()), groupKeys(store, owner));
        assertEquals(
            Arrays.asList(0, 1),
            store.snapshot(owner)
                .groups()
                .stream()
                .map(HomeGroup::ordinal)
                .collect(Collectors.toList()));
    }

    @Test
    void groupingAcrossNetworksKeepsHomeBucketOrdersAndTerminalPlacementsIndependent() throws Exception {
        ResourcePinStore store = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        WebPrincipal owner = WebPrincipal.admin();
        StableKey firstGrid = TestGridFixtures
            .resolvedKey(new TestGrid(88701, false, AEControllerState.CONTROLLER_ONLINE));
        StableKey secondGrid = TestGridFixtures
            .resolvedKey(new TestGrid(88702, false, AEControllerState.CONTROLLER_ONLINE));
        Pin first = addPin(store, owner, firstGrid, 91);
        Pin second = addPin(store, owner, secondGrid, 92);
        Pin third = addPin(store, owner, firstGrid, 93);
        Pin fourth = addPin(store, owner, secondGrid, 94);
        HomeGroup production = store.createGroup(owner, "Production");
        HomeGroup storage = store.createGroup(owner, "Storage");
        HomeConfig configuration = new HomeConfig(
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
        assertTrue(
            store.update(
                owner,
                first.pinKey(),
                null,
                null,
                null,
                configuration,
                new HomeGroupSelection(production.groupKey())));
        assertTrue(
            store.update(owner, third.pinKey(), null, null, null, null, new HomeGroupSelection(production.groupKey())));
        assertTrue(
            store.update(owner, second.pinKey(), null, null, null, null, new HomeGroupSelection(storage.groupKey())));
        assertEquals(Arrays.asList(first.pinKey(), third.pinKey()), pinKeys(store, owner, production.groupKey()));
        assertEquals(Collections.singletonList(fourth.pinKey()), pinKeys(store, owner, null));
        assertTrue(
            store.update(owner, third.pinKey(), null, null, new Move(Placement.HOME, Direction.EARLIER), null, null));
        assertEquals(Arrays.asList(third.pinKey(), first.pinKey()), pinKeys(store, owner, production.groupKey()));
        assertTrue(
            store.update(owner, third.pinKey(), null, null, null, null, new HomeGroupSelection(storage.groupKey())));
        assertEquals(Collections.singletonList(first.pinKey()), pinKeys(store, owner, production.groupKey()));
        assertEquals(Arrays.asList(second.pinKey(), third.pinKey()), pinKeys(store, owner, storage.groupKey()));
        assertEquals(1, pin(store, owner, third.pinKey()).terminalOrder());
        assertEquals(configuration, pin(store, owner, first.pinKey()).homeConfig());
        assertEquals(0, pin(store, owner, first.pinKey()).homeOrder());
        assertEquals(1, pin(store, owner, third.pinKey()).homeOrder());
        ResourcePinStore.Snapshot saved = store.snapshot(owner);
        worlds.close(TestGridFixtures.Worlds.source(gridSave));
        ResourcePinStore reopened = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        assertEquals(saved, reopened.snapshot(owner));
    }

    @Test
    void deletingAGroupAppendsItsPinsToUngroupedWithoutDeletingTheirConfiguration() throws Exception {
        ResourcePinStore store = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        WebPrincipal owner = WebPrincipal.admin();
        StableKey grid = TestGridFixtures.resolvedKey(new TestGrid(88703, false, AEControllerState.CONTROLLER_ONLINE));
        Pin first = addPin(store, owner, grid, 95);
        Pin second = addPin(store, owner, grid, 96);
        Pin ungrouped = addPin(store, owner, grid, 97);
        HomeGroup group = store.createGroup(owner, "Production");
        HomeGroup empty = store.createGroup(owner, "Empty");
        HomeConfig configuration = new HomeConfig(true, false, false, false, true, false, true, 3600000, true, 900000);
        store.update(owner, first.pinKey(), null, null, null, configuration, new HomeGroupSelection(group.groupKey()));
        store.update(owner, second.pinKey(), null, null, null, null, new HomeGroupSelection(group.groupKey()));
        store.update(owner, second.pinKey(), null, null, new Move(Placement.HOME, Direction.EARLIER), null, null);
        store.removeGroup(owner, group.groupKey());
        assertEquals(Arrays.asList(ungrouped.pinKey(), second.pinKey(), first.pinKey()), pinKeys(store, owner, null));
        assertEquals(configuration, pin(store, owner, first.pinKey()).homeConfig());
        assertEquals(first.terminalOrder(), pin(store, owner, first.pinKey()).terminalOrder());
        assertEquals(second.terminalOrder(), pin(store, owner, second.pinKey()).terminalOrder());
        assertEquals(Collections.singletonList(empty.groupKey()), groupKeys(store, owner));
        assertEquals(
            0,
            store.snapshot(owner)
                .groups()
                .get(0)
                .ordinal());
        store.removeGroup(owner, group.groupKey());
        assertEquals(
            3,
            store.list(owner)
                .size());
    }

    @Test
    void invalidAssignmentsAreAtomicAndDisablingHomeClearsOnlyHomeMembership() throws Exception {
        ResourcePinStore store = new ResourcePinStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)));
        WebPrincipal owner = WebPrincipal.admin();
        WebPrincipal other = TestGridFixtures.principal(4);
        StableKey grid = TestGridFixtures.resolvedKey(new TestGrid(88704, false, AEControllerState.CONTROLLER_ONLINE));
        Pin first = addPin(store, owner, grid, 98);
        Pin second = addPin(store, owner, grid, 99);
        HomeGroup group = store.createGroup(owner, "Production");
        HomeGroup foreign = store.createGroup(other, "Private");
        store.update(owner, first.pinKey(), null, null, null, null, new HomeGroupSelection(group.groupKey()));
        store.update(owner, second.pinKey(), null, null, null, null, new HomeGroupSelection(group.groupKey()));
        ResourcePinStore.Snapshot before = store.snapshot(owner);
        HomeConfig altered = new HomeConfig(false, false, false, false, false, false, false, 60000, false, 60000);
        assertThrows(
            IllegalArgumentException.class,
            () -> store
                .update(owner, first.pinKey(), false, null, null, altered, new HomeGroupSelection(foreign.groupKey())));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.update(
                owner,
                first.pinKey(),
                null,
                null,
                null,
                altered,
                new HomeGroupSelection(TestGridFixtures.key(100))));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.update(owner, first.pinKey(), null, false, null, altered, new HomeGroupSelection(null)));
        assertFalse(
            store.update(other, first.pinKey(), null, null, null, null, new HomeGroupSelection(foreign.groupKey())));
        assertEquals(before, store.snapshot(owner));
        assertTrue(store.update(owner, first.pinKey(), null, false, null, null, null));
        assertNull(pin(store, owner, first.pinKey()).homeGroupKey());
        assertNull(pin(store, owner, first.pinKey()).homeOrder());
        assertEquals(0, pin(store, owner, first.pinKey()).terminalOrder());
        assertEquals(0, pin(store, owner, second.pinKey()).homeOrder());
        assertTrue(store.update(owner, first.pinKey(), null, true, null, null, null));
        assertEquals(Collections.singletonList(first.pinKey()), pinKeys(store, owner, null));
        assertNull(pin(store, owner, first.pinKey()).homeGroupKey());
        store.remove(owner, second.pinKey());
        assertTrue(pinKeys(store, owner, group.groupKey()).isEmpty());
        assertEquals(Collections.singletonList(group.groupKey()), groupKeys(store, owner));
    }

    @Test
    void failedGroupDeletionRollsBackPinMovesAndGroupOrderTogether() throws Exception {
        WorldDatabase database = worlds.database(TestGridFixtures.Worlds.source(gridSave));
        ResourcePinStore store = new ResourcePinStore(database);
        WebPrincipal owner = WebPrincipal.admin();
        StableKey grid = TestGridFixtures.resolvedKey(new TestGrid(88705, false, AEControllerState.CONTROLLER_ONLINE));
        Pin pin = addPin(store, owner, grid, 101);
        HomeGroup group = store.createGroup(owner, "Production");
        store.update(owner, pin.pinKey(), null, null, null, null, new HomeGroupSelection(group.groupKey()));
        ResourcePinStore.Snapshot before = store.snapshot(owner);
        database.write(db -> {
            try (Statement sql = db.createStatement()) {
                sql.execute(
                    "CREATE TRIGGER reject_group_delete BEFORE DELETE ON home_pin_groups BEGIN SELECT RAISE(ABORT, 'Test failure'); END");
            }
            return null;
        });
        assertThrows(IOException.class, () -> store.removeGroup(owner, group.groupKey()));
        assertEquals(before, store.snapshot(owner));
        database.write(db -> {
            try (Statement sql = db.createStatement()) {
                sql.execute("DROP TRIGGER reject_group_delete");
            }
            return null;
        });
        store.removeGroup(owner, group.groupKey());
        assertEquals(Collections.singletonList(pin.pinKey()), pinKeys(store, owner, null));
    }

    private static List<StableKey> groupKeys(ResourcePinStore store, WebPrincipal owner) throws Exception {
        return store.snapshot(owner)
            .groups()
            .stream()
            .map(HomeGroup::groupKey)
            .collect(Collectors.toList());
    }

    private static Pin addPin(ResourcePinStore store, WebPrincipal owner, StableKey grid, int itemId) throws Exception {
        StableKey item = TestGridFixtures.key(itemId);
        ResourceDescriptor descriptor = new ResourceDescriptor(
            "test",
            "item_" + itemId,
            0,
            0,
            "Item " + itemId,
            ResourceType.ITEM,
            item,
            item);
        assertTrue(store.add(owner, grid, item, descriptor, Placement.HOME));
        assertTrue(store.add(owner, grid, item, null, Placement.TERMINAL));
        return store.list(owner)
            .stream()
            .filter(
                pin -> pin.itemKey()
                    .equals(item))
            .findFirst()
            .get();
    }

    private static Pin pin(ResourcePinStore store, WebPrincipal owner, StableKey key) throws Exception {
        return store.list(owner)
            .stream()
            .filter(
                pin -> pin.pinKey()
                    .equals(key))
            .findFirst()
            .get();
    }

    private static List<StableKey> pinKeys(ResourcePinStore store, WebPrincipal owner, StableKey group)
        throws Exception {
        return store.list(owner)
            .stream()
            .filter(pin -> pin.homeOrder() != null && Objects.equals(group, pin.homeGroupKey()))
            .sorted(Comparator.comparingInt(Pin::homeOrder))
            .map(Pin::pinKey)
            .collect(Collectors.toList());
    }
}
