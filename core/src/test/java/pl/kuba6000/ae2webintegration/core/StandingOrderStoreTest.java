package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.CONTROLLER_ONLINE;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;
import pl.kuba6000.ae2webintegration.core.standingorders.AmountPolicy;
import pl.kuba6000.ae2webintegration.core.standingorders.Condition;
import pl.kuba6000.ae2webintegration.core.standingorders.CpuTarget;
import pl.kuba6000.ae2webintegration.core.standingorders.CraftAction;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException.Reason;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderDefinition;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore.Entry;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore.Invalid;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore.OrderLimitReached;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore.UnknownResource;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore.Valid;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Arbitrary fixture quantities and seeds.
class StandingOrderStoreTest extends GridTestScope {

    private static final StableKey PLATE = TestGridFixtures.key(9101);
    private static final StableKey INGOT = TestGridFixtures.key(9102);
    private static final StableKey DUST = TestGridFixtures.key(9103);
    private static final StableKey CPU = TestGridFixtures.key(9104);

    private final AtomicInteger limit = new AtomicInteger(128);
    private WorldDatabase database;
    private StandingOrderStore store;
    private StableKey grid;

    private void open() throws IOException {
        database = worlds.database(TestGridFixtures.Worlds.source(gridSave));
        store = new StandingOrderStore(database, limit::get);
        grid = grid(88501);
    }

    private static StableKey grid(int seed) {
        return TestGridFixtures.resolvedKey(new TestGridFixtures.TestGrid(seed, false, CONTROLLER_ONLINE));
    }

    private static ResourceDescriptor description(StableKey key, String name) {
        return new ResourceDescriptor("test", name.toLowerCase(), 3, 5, name, ResourceType.ITEM, key, null);
    }

    private static Map<StableKey, ResourceDescriptor> descriptions() {
        Map<StableKey, ResourceDescriptor> result = new HashMap<>();
        result.put(PLATE, description(PLATE, "Plate"));
        result.put(INGOT, description(INGOT, "Ingot"));
        result.put(DUST, description(DUST, "Dust"));
        return result;
    }

    private static StandingOrderDefinition simple(String name, StableKey resource) {
        return new StandingOrderDefinition(
            name,
            true,
            new Condition.StockBelow(resource, 100),
            new CraftAction(resource, new AmountPolicy.Fixed(64), new CraftingOptions(false, false), CpuTarget.any()),
            0);
    }

    private static StandingOrderDefinition full() {
        return new StandingOrderDefinition(
            "Zlecenie stałe é中",
            false,
            new Condition.All(
                Arrays.asList(
                    new Condition.StockBelow(PLATE, 10_000),
                    new Condition.Any(
                        Arrays.asList(
                            new Condition.StockAtLeast(INGOT, 50_000),
                            new Condition.Not(new Condition.IdleCpusAtLeast(2)))),
                    new Condition.StockAtLeast(DUST, Long.MAX_VALUE))),
            new CraftAction(
                PLATE,
                new AmountPolicy.FillTo(20_000, 4_096),
                new CraftingOptions(true, true),
                CpuTarget.specific(CPU)),
            86_400);
    }

    private static Invalid invalid(Entry entry) {
        assertInstanceOf(Invalid.class, entry);
        return (Invalid) entry;
    }

    private static Valid valid(Entry entry) {
        assertInstanceOf(Valid.class, entry);
        return (Valid) entry;
    }

    @Test
    void createdOrdersRoundTripEveryFieldInOrdinalOrderAcrossReopen() throws Exception {
        open();
        WebPrincipal player = TestGridFixtures.principal(3);
        Valid first = store.create(grid, player, full(), descriptions());
        Valid second = store.create(grid, WebPrincipal.admin(), simple("Second", INGOT), Collections.emptyMap());
        Valid third = store.create(grid, WebPrincipal.localhost(), simple("Third", DUST), Collections.emptyMap());
        assertEquals(Arrays.asList(0, 1, 2), Arrays.asList(first.ordinal(), second.ordinal(), third.ordinal()));
        assertEquals(full(), first.definition());
        assertEquals(TestGridFixtures.playerIdentity(3).uuid.toString(), first.createdBy());
        assertEquals("admin", second.createdBy());
        assertEquals("admin", third.createdBy());
        assertEquals(3, new HashSet<>(Arrays.asList(first.key(), second.key(), third.key())).size());
        List<Entry> expected = Arrays.asList(first, second, third);
        List<Entry> listed = store.list(grid);
        assertEquals(expected, listed);
        for (int index = 0; index < listed.size(); index++) {
            Entry entry = listed.get(index);
            assertEquals(
                expected.get(index)
                    .key(),
                entry.key());
            assertEquals(index, entry.ordinal());
            assertEquals(
                expected.get(index)
                    .createdBy(),
                entry.createdBy());
        }
        assertEquals(second, store.get(grid, second.key()));
        worlds.close(TestGridFixtures.Worlds.source(gridSave));
        database = worlds.database(TestGridFixtures.Worlds.source(gridSave));
        store = new StandingOrderStore(database, limit::get);
        assertEquals(expected, store.list(grid));
    }

    @Test
    void ordersBelongToTheirGridOnly() throws Exception {
        open();
        StableKey other = grid(88502);
        Valid mine = store.create(grid, WebPrincipal.admin(), simple("Mine", PLATE), descriptions());
        Valid theirs = store.create(other, WebPrincipal.admin(), simple("Theirs", PLATE), Collections.emptyMap());
        assertEquals(Collections.singletonList(mine), store.list(grid));
        assertEquals(Collections.singletonList(theirs), store.list(other));
        assertEquals(0, mine.ordinal());
        assertEquals(0, theirs.ordinal());
        assertNull(store.get(other, mine.key()));
        assertFalse(store.replace(other, mine.key(), simple("Hijack", PLATE), Collections.emptyMap()));
        assertFalse(store.delete(other, mine.key()));
        assertEquals(mine, store.get(grid, mine.key()));
        assertTrue(
            store.list(TestGridFixtures.key(1))
                .isEmpty());
    }

    @Test
    void listAllReturnsEveryGridThatHasOrdersWithTheirOrdersInDisplayOrder() throws Exception {
        open();
        StableKey other = grid(88503);
        StableKey empty = grid(88504);
        Valid first = store.create(grid, WebPrincipal.admin(), simple("First", PLATE), descriptions());
        Valid second = store.create(grid, WebPrincipal.admin(), simple("Second", INGOT), descriptions());
        Valid theirs = store.create(other, WebPrincipal.admin(), simple("Theirs", DUST), descriptions());
        Map<StableKey, List<Entry>> all = store.listAll();
        assertEquals(new HashSet<>(Arrays.asList(grid, other)), all.keySet());
        assertFalse(all.containsKey(empty));
        assertEquals(Arrays.<Entry>asList(first, second), all.get(grid));
        assertEquals(Collections.<Entry>singletonList(theirs), all.get(other));
        store.delete(other, theirs.key());
        assertEquals(
            Collections.singleton(grid),
            store.listAll()
                .keySet());
    }

    @Test
    void snapshotReadsTheGridsOrdersWithTheDescriptionsOfTheResourcesTheyReferenceOnly() throws Exception {
        open();
        StableKey other = grid(88505);
        Valid first = store.create(grid, WebPrincipal.admin(), full(), descriptions());
        Valid second = store.create(grid, WebPrincipal.admin(), simple("Second", PLATE), Collections.emptyMap());
        store.create(other, WebPrincipal.admin(), simple("Theirs", DUST), descriptions());
        StandingOrderStore.Snapshot snapshot = store.snapshot(grid);
        assertEquals(Arrays.<Entry>asList(first, second), snapshot.orders());
        Map<StableKey, ResourceDescriptor> expected = new HashMap<>();
        for (StableKey resource : Arrays.asList(PLATE, INGOT, DUST))
            expected.put(resource, descriptions().get(resource));
        assertEquals(expected, snapshot.resources());
        assertTrue(store.replace(grid, first.key(), simple("First", PLATE), Collections.emptyMap()));
        StandingOrderStore.Snapshot reduced = store.snapshot(grid);
        assertEquals(
            Collections.singleton(PLATE),
            reduced.resources()
                .keySet());
        assertEquals(
            Collections.singleton(DUST),
            store.snapshot(other)
                .resources()
                .keySet());
        StandingOrderStore.Snapshot empty = store.snapshot(TestGridFixtures.key(1));
        assertTrue(
            empty.orders()
                .isEmpty());
        assertTrue(
            empty.resources()
                .isEmpty());
    }

    @Test
    void replaceChangesTheDefinitionAndResourcesButKeepsIdentityOrderAndCreator() throws Exception {
        open();
        Valid first = store.create(grid, TestGridFixtures.principal(3), full(), descriptions());
        Valid second = store.create(grid, WebPrincipal.admin(), simple("Second", PLATE), Collections.emptyMap());
        StandingOrderDefinition changed = simple("Renamed", DUST);
        assertTrue(store.replace(grid, first.key(), changed, Collections.emptyMap()));
        Valid reloaded = valid(store.get(grid, first.key()));
        assertEquals(changed, reloaded.definition());
        assertEquals(first.key(), reloaded.key());
        assertEquals(first.ordinal(), reloaded.ordinal());
        assertEquals(first.createdBy(), reloaded.createdBy());
        assertEquals(Arrays.asList(reloaded, second), store.list(grid));
        assertEquals("DUST", resourceNames(first.key()));
        assertEquals(
            "Dust|Plate",
            catalogueNames(),
            "Ingot is no longer referenced by anything, so its catalogue row is released");
        assertFalse(store.replace(grid, TestGridFixtures.key(5), changed, Collections.emptyMap()));
    }

    @Test
    void deleteCascadesResourceReferencesAndReleasesOnlyUnreferencedCatalogueRows() throws Exception {
        open();
        Valid first = store.create(grid, WebPrincipal.admin(), full(), descriptions());
        Valid second = store.create(grid, WebPrincipal.admin(), simple("Second", PLATE), Collections.emptyMap());
        assertEquals("Dust|Ingot|Plate", catalogueNames());
        assertTrue(store.delete(grid, first.key()));
        assertNull(store.get(grid, first.key()));
        assertEquals(Collections.singletonList(second), store.list(grid));
        assertEquals(
            "0",
            scalar("SELECT count(*) FROM standing_order_resources WHERE order_key = '" + first.key() + "'"));
        assertEquals("Plate", catalogueNames());
        assertTrue(store.delete(grid, second.key()));
        assertEquals("", catalogueNames());
        assertEquals("0", scalar("SELECT count(*) FROM standing_order_resources"));
        assertFalse(store.delete(grid, second.key()));
    }

    @Test
    void anOrderKeepsItsCatalogueRowsWhenAnotherOwnerReleasesTheSameResource() throws Exception {
        open();
        ResourcePinStore pins = new ResourcePinStore(database);
        assertTrue(
            pins.add(WebPrincipal.admin(), grid, PLATE, description(PLATE, "Plate"), ResourcePinStore.Placement.HOME));
        Valid order = store.create(grid, WebPrincipal.admin(), simple("Plates", PLATE), Collections.emptyMap());
        List<ResourcePinStore.Pin> pinned = pins.list(WebPrincipal.admin());
        assertEquals(1, pinned.size());
        pins.remove(
            WebPrincipal.admin(),
            pinned.get(0)
                .pinKey());
        assertEquals("Plate", catalogueNames());
        assertEquals(order, store.get(grid, order.key()));
    }

    @Test
    void creationIsLimitedPerGridWithTheCurrentConfiguredMaximum() throws Exception {
        open();
        StableKey other = grid(88502);
        limit.set(2);
        store.create(grid, WebPrincipal.admin(), simple("One", PLATE), descriptions());
        Valid second = store.create(grid, WebPrincipal.admin(), simple("Two", PLATE), Collections.emptyMap());
        assertThrows(
            OrderLimitReached.class,
            () -> store.create(grid, WebPrincipal.admin(), simple("Three", PLATE), Collections.emptyMap()));
        assertEquals(
            2,
            store.list(grid)
                .size());
        store.create(other, WebPrincipal.admin(), simple("Other grid", PLATE), Collections.emptyMap());
        assertTrue(store.delete(grid, second.key()));
        assertEquals(
            1,
            store.create(grid, WebPrincipal.admin(), simple("Three", PLATE), Collections.emptyMap())
                .ordinal());
        limit.set(3);
        store.create(grid, WebPrincipal.admin(), simple("Four", PLATE), Collections.emptyMap());
        limit.set(1);
        assertThrows(
            OrderLimitReached.class,
            () -> store.create(grid, WebPrincipal.admin(), simple("Five", PLATE), Collections.emptyMap()));
        assertEquals(
            3,
            store.list(grid)
                .size());
        assertTrue(
            store.replace(
                grid,
                store.list(grid)
                    .get(0)
                    .key(),
                simple("Edit at capacity", PLATE),
                Collections.emptyMap()));
    }

    @Test
    void anUndecodableRowIsReportedWithItsReasonWithoutHidingTheOtherOrders() throws Exception {
        open();
        Valid good = store.create(grid, WebPrincipal.admin(), full(), descriptions());
        Valid futureVersion = store.create(grid, WebPrincipal.admin(), simple("Future", PLATE), Collections.emptyMap());
        Valid broken = store.create(grid, WebPrincipal.admin(), simple("Broken", INGOT), Collections.emptyMap());
        Valid missingSlot = store
            .create(grid, TestGridFixtures.principal(4), simple("Slot", DUST), Collections.emptyMap());
        Valid tail = store.create(grid, WebPrincipal.admin(), simple("Tail", PLATE), Collections.emptyMap());
        execute(
            "UPDATE standing_orders SET definition_version = 99 WHERE order_key = '" + futureVersion.key() + "'",
            "UPDATE standing_orders SET definition = '{\"name\":' WHERE order_key = '" + broken.key() + "'",
            "DELETE FROM standing_order_resources WHERE order_key = '" + missingSlot.key() + "'");
        List<Entry> entries = store.list(grid);
        assertEquals(5, entries.size());
        assertEquals(good, entries.get(0));
        assertEquals(tail, entries.get(4));
        Invalid version = invalid(entries.get(1));
        assertEquals(futureVersion.key(), version.key());
        assertEquals(1, version.ordinal());
        assertEquals("admin", version.createdBy());
        assertEquals(
            Reason.UNSUPPORTED_VERSION,
            version.failure()
                .reason());
        Invalid malformed = invalid(entries.get(2));
        assertEquals(broken.key(), malformed.key());
        assertEquals(
            Reason.MALFORMED_JSON,
            malformed.failure()
                .reason());
        Invalid slot = invalid(entries.get(3));
        assertEquals(missingSlot.key(), slot.key());
        assertEquals(
            Reason.INVALID_RESOURCE_SLOT,
            slot.failure()
                .reason());
        assertEquals(
            Reason.INVALID_RESOURCE_SLOT,
            invalid(store.get(grid, missingSlot.key())).failure()
                .reason());
        assertEquals(
            Reason.MALFORMED_JSON,
            invalid(store.get(grid, broken.key())).failure()
                .reason());
        assertTrue(store.replace(grid, broken.key(), simple("Repaired", INGOT), Collections.emptyMap()));
        assertEquals(
            "Repaired",
            valid(store.get(grid, broken.key())).definition()
                .name());
        assertTrue(store.delete(grid, futureVersion.key()));
        assertEquals(
            4,
            store.list(grid)
                .size());
    }

    @Test
    void aResourceTableWithAGapIsAnInvalidOrderNotALoadFailure() throws Exception {
        open();
        Valid order = store.create(grid, WebPrincipal.admin(), full(), descriptions());
        Valid other = store.create(grid, WebPrincipal.admin(), simple("Other", PLATE), Collections.emptyMap());
        execute("UPDATE standing_order_resources SET slot = slot + 10 WHERE order_key = '" + order.key() + "'");
        Invalid gapped = invalid(store.get(grid, order.key()));
        assertEquals(
            Reason.INVALID_RESOURCE_TABLE,
            gapped.failure()
                .reason());
        assertEquals(other, store.get(grid, other.key()));
    }

    @Test
    void invalidInputIsRejectedBeforeAnyChange() throws Exception {
        open();
        Valid existing = store.create(grid, WebPrincipal.admin(), simple("Existing", PLATE), descriptions());
        StandingOrderDefinition unnamed = new StandingOrderDefinition(
            "",
            true,
            new Condition.StockBelow(PLATE, 100),
            new CraftAction(PLATE, new AmountPolicy.Fixed(64), new CraftingOptions(false, false), CpuTarget.any()),
            0);
        InvalidStandingOrderException create = assertThrows(
            InvalidStandingOrderException.class,
            () -> store.create(grid, WebPrincipal.admin(), unnamed, Collections.emptyMap()));
        assertEquals(Reason.NAME_LENGTH, create.reason());
        assertThrows(
            InvalidStandingOrderException.class,
            () -> store.replace(grid, existing.key(), unnamed, Collections.emptyMap()));
        assertEquals(Collections.singletonList(existing), store.list(grid));
        assertThrows(
            UnknownResource.class,
            () -> store.create(
                grid,
                WebPrincipal.admin(),
                simple("Unknown", TestGridFixtures.key(77)),
                Collections.emptyMap()));
        assertThrows(
            UnknownResource.class,
            () -> store
                .replace(grid, existing.key(), simple("Unknown", TestGridFixtures.key(77)), Collections.emptyMap()));
        Map<StableKey, ResourceDescriptor> mismatched = Collections.singletonMap(INGOT, description(PLATE, "Plate"));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.create(grid, WebPrincipal.admin(), simple("Mismatch", INGOT), mismatched));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.create(
                TestGridFixtures.key(1),
                WebPrincipal.admin(),
                simple("No grid", PLATE),
                Collections.emptyMap()));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.create(grid, WebPrincipal.anonymous(), simple("Anonymous", PLATE), Collections.emptyMap()));
        assertEquals(Collections.singletonList(existing), store.list(grid));
        assertEquals("Plate", catalogueNames(), "A rejected write leaves no catalogue rows behind");
    }

    @Test
    void suppliedDescriptionsRefreshTheCatalogueOnlyForReferencedResources() throws Exception {
        open();
        Map<StableKey, ResourceDescriptor> supplied = descriptions();
        store.create(grid, WebPrincipal.admin(), simple("Plates", PLATE), supplied);
        assertEquals("Plate", catalogueNames());
        Map<StableKey, ResourceDescriptor> renamed = new HashMap<>(supplied);
        renamed.put(PLATE, description(PLATE, "Renamed plate"));
        store.create(grid, WebPrincipal.admin(), simple("More plates", PLATE), renamed);
        assertEquals("Renamed plate", catalogueNames());
    }

    private @NotNull String resourceNames(@NotNull StableKey order) throws IOException {
        return rows(
            "SELECT upper(h.registry_path) FROM standing_order_resources r JOIN history_resources h ON h.id = r.resource_id "
                + "WHERE r.order_key = '"
                + order
                + "' ORDER BY r.slot");
    }

    private @NotNull String catalogueNames() throws IOException {
        return rows("SELECT display_name FROM history_resources ORDER BY display_name");
    }

    private @NotNull String rows(@NotNull String query) throws IOException {
        return database.read(db -> {
            List<String> values = new ArrayList<>();
            try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery(query)) {
                while (rows.next()) values.add(rows.getString(1));
            }
            return String.join("|", values);
        });
    }

    private @NotNull String scalar(@NotNull String query) throws IOException {
        return database.read(db -> {
            try (PreparedStatement sql = db.prepareStatement(query); ResultSet rows = sql.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        });
    }

    private void execute(@NotNull String... statements) throws IOException {
        database.write(db -> {
            try (Statement sql = db.createStatement()) {
                for (String statement : statements) sql.execute(statement);
            }
            return null;
        });
    }
}
