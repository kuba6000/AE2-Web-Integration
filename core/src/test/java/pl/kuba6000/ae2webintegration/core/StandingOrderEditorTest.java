package pl.kuba6000.ae2webintegration.core;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.CoreEngine.GRID_IDENTITIES;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.CONTROLLER_ONLINE;

import java.io.IOException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.standingorders.AmountPolicy;
import pl.kuba6000.ae2webintegration.core.standingorders.Condition;
import pl.kuba6000.ae2webintegration.core.standingorders.CpuTarget;
import pl.kuba6000.ae2webintegration.core.standingorders.CraftAction;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderDefinition;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEditor;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEditor.RunResult;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEditor.TooManyReadRequests;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEngine;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.storage.InMemoryStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

/**
 * Store and engine changes of one standing order as the HTTP layer drives them: serialized, and limited in how often
 * they may cost the network a read.
 */
@SuppressWarnings("PMD.AvoidMagicNumbers") // Arbitrary fixture seeds and times.
class StandingOrderEditorTest extends GridTestScope {

    private static final long INTERVAL = MILLISECONDS.toNanos(StandingOrderEditor.MIN_READ_INTERVAL_MILLIS);
    private static final StableKey PLATE = TestGridFixtures.key(9201);
    private static final StableKey INGOT = TestGridFixtures.key(9202);
    private static final StableKey DUST = TestGridFixtures.key(9203);

    private final ConfigSettings.StandingOrders settings = new ConfigSettings.StandingOrders();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AtomicBoolean blockCreation = new AtomicBoolean();
    private final CountDownLatch creationEntered = new CountDownLatch(1);
    private final CountDownLatch creationMayFinish = new CountDownLatch(1);
    private StorageTrackingService service;
    private StorageTrackingCoordinator coordinator;
    private StandingOrderEngine engine;
    private StandingOrderStore store;
    private StandingOrderEditor editor;
    private StableKey grid;

    @BeforeEach
    void open() throws Exception {
        grid = TestGridFixtures.resolvedKey(new TestGridFixtures.TestGrid(88601, false, CONTROLLER_ONLINE));
        service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
        coordinator = new StorageTrackingCoordinator(
            service,
            GRID_IDENTITIES,
            new ConfigSettings.ResourceTracking(),
            new TerminalRegistry(new LiveHub()));
        engine = new StandingOrderEngine(
            coordinator,
            GRID_IDENTITIES,
            () -> settings,
            Collections::emptyMap,
            System::nanoTime);
        store = new StandingOrderStore(worlds.database(TestGridFixtures.Worlds.source(gridSave)), () -> {
            if (blockCreation.get()) {
                creationEntered.countDown();
                try {
                    assertTrue(creationMayFinish.await(10, SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return 128;
        });
        editor = new StandingOrderEditor(store, engine, clock::get);
    }

    @AfterEach
    void close() {
        engine.close();
        coordinator.close();
        service.close();
        AE2Controller.itemIdentities.clear();
    }

    private static ResourceDescriptor description(StableKey key, String name) {
        return new ResourceDescriptor("test", name.toLowerCase(), 0, 0, name, ResourceType.ITEM, key, null);
    }

    private static Map<StableKey, ResourceDescriptor> descriptions() {
        Map<StableKey, ResourceDescriptor> result = new HashMap<>();
        result.put(PLATE, description(PLATE, "Plate"));
        result.put(INGOT, description(INGOT, "Ingot"));
        result.put(DUST, description(DUST, "Dust"));
        return result;
    }

    private static StandingOrderDefinition order(String name, boolean enabled, StableKey trigger, StableKey crafted) {
        return new StandingOrderDefinition(
            name,
            enabled,
            new Condition.StockBelow(trigger, 100),
            new CraftAction(crafted, new AmountPolicy.Fixed(64), new CraftingOptions(false, false), CpuTarget.any()),
            0);
    }

    private boolean engineKnows(StableKey order) {
        engine.tick(System.currentTimeMillis());
        return engine.snapshot(order) != null;
    }

    private boolean replace(StableKey grid, StableKey order, StandingOrderDefinition definition,
        Map<StableKey, ResourceDescriptor> descriptions) throws Exception {
        return editor.update(grid, order, current -> definition, ignored -> descriptions);
    }

    private void execute(String... statements) throws IOException {
        worlds.database(TestGridFixtures.Worlds.source(gridSave))
            .write(db -> {
                try (Statement sql = db.createStatement()) {
                    for (String statement : statements) sql.execute(statement);
                }
                return null;
            });
    }

    private void waitOutTheInterval() {
        clock.addAndGet(INTERVAL);
    }

    @Test
    void aCreatedOrderIsStoredAndPostedAndADeletedOneIsRemovedFromBoth() throws Exception {
        StandingOrderStore.Valid created = editor
            .create(grid, WebPrincipal.admin(), order("Keep plates", true, PLATE, PLATE), descriptions());
        assertEquals(created, store.get(grid, created.key()));
        assertTrue(engineKnows(created.key()));
        assertTrue(editor.delete(grid, created.key()));
        assertNull(store.get(grid, created.key()));
        assertFalse(engineKnows(created.key()));
        assertFalse(editor.delete(grid, created.key()), "Deleting a missing order reports it");
    }

    @Test
    void aReplacementKeepsTheOrdersIdentityAndIsPostedToTheEngine() throws Exception {
        StandingOrderStore.Valid created = editor
            .create(grid, TestGridFixtures.principal(3), order("Keep plates", true, PLATE, PLATE), descriptions());
        StandingOrderDefinition changed = order("Renamed", false, PLATE, PLATE);
        assertTrue(replace(grid, created.key(), changed, Collections.emptyMap()));
        assertEquals(
            new StandingOrderStore.Valid(created.key(), created.ordinal(), created.createdBy(), changed),
            store.get(grid, created.key()));
        assertTrue(engineKnows(created.key()));
        assertFalse(replace(grid, TestGridFixtures.key(5), changed, Collections.emptyMap()));
    }

    @Test
    void anEditIsDerivedFromTheStoredOrderAndARefusedEditChangesNothing() throws Exception {
        StandingOrderStore.Valid created = editor
            .create(grid, WebPrincipal.admin(), order("Keep plates", true, PLATE, PLATE), descriptions());
        StableKey[] seen = new StableKey[1];
        assertTrue(editor.update(grid, created.key(), current -> {
            seen[0] = current.key();
            return order("Disabled", false, PLATE, PLATE);
        }, ignored -> Collections.emptyMap()));
        assertEquals(created.key(), seen[0]);
        assertFalse(
            ((StandingOrderStore.Valid) store.get(grid, created.key())).definition()
                .enabled());
        assertThrows(InvalidStandingOrderException.class, () -> editor.update(grid, created.key(), current -> {
            throw new InvalidStandingOrderException(InvalidStandingOrderException.Reason.MISSING_FIELD, "name");
        }, ignored -> Collections.emptyMap()));
        assertFalse(
            editor.update(
                grid,
                TestGridFixtures.key(5),
                current -> fail("A missing order is never edited"),
                ignored -> Collections.emptyMap()));
        assertEquals(
            "Disabled",
            ((StandingOrderStore.Valid) store.get(grid, created.key())).definition()
                .name());
    }

    @Test
    void anEditThatChangesNothingWritesAndPostsNothing() throws Exception {
        StandingOrderDefinition definition = order("Keep plates", true, PLATE, PLATE);
        StandingOrderStore.Valid created = editor.create(grid, WebPrincipal.admin(), definition, descriptions());
        int[] lookups = new int[1];
        assertTrue(editor.update(grid, created.key(), current -> definition, derived -> {
            lookups[0]++;
            return Collections.emptyMap();
        }));
        assertEquals(0, lookups[0], "An identical definition is not stored again");
        assertTrue(editor.update(grid, created.key(), current -> order("Renamed", true, PLATE, PLATE), derived -> {
            lookups[0]++;
            return Collections.emptyMap();
        }));
        assertEquals(1, lookups[0]);
        assertEquals(
            "Renamed",
            ((StandingOrderStore.Valid) store.get(grid, created.key())).definition()
                .name());
    }

    @Test
    void anOrderOfASwitchedOffNetworkIsNotRunnableAndDoesNotUseTheAllowance() throws Exception {
        StandingOrderStore.Valid created = editor
            .create(grid, WebPrincipal.admin(), order("Keep plates", true, PLATE, PLATE), descriptions());
        waitOutTheInterval();
        settings.enabled = false;
        assertTrue(engineKnows(created.key()));
        assertEquals(RunResult.NOT_RUNNABLE, editor.run(grid, created.key()));
        settings.enabled = true;
        engineKnows(created.key());
        assertEquals(RunResult.QUEUED, editor.run(grid, created.key()));
    }

    @Test
    void invalidInputAndUnknownResourcesLeaveNothingBehindAndDoNotUseTheReadAllowance() throws Exception {
        StandingOrderDefinition unnamed = order("", true, PLATE, PLATE);
        assertThrows(
            InvalidStandingOrderException.class,
            () -> editor.create(grid, WebPrincipal.admin(), unnamed, descriptions()));
        assertThrows(
            StandingOrderStore.UnknownResource.class,
            () -> editor
                .create(grid, WebPrincipal.admin(), order("Unknown", true, PLATE, PLATE), Collections.emptyMap()));
        assertTrue(
            store.list(grid)
                .isEmpty());
        StandingOrderStore.Valid created = editor
            .create(grid, WebPrincipal.admin(), order("Keep plates", true, PLATE, PLATE), descriptions());
        assertNotNull(created);
    }

    @Test
    void creatingOrdersAndRunningThemShareOneReadAllowancePerNetwork() throws Exception {
        StandingOrderStore.Valid first = editor
            .create(grid, WebPrincipal.admin(), order("First", true, PLATE, PLATE), descriptions());
        assertThrows(
            TooManyReadRequests.class,
            () -> editor.create(grid, WebPrincipal.admin(), order("Second", true, INGOT, INGOT), descriptions()));
        assertEquals(
            1,
            store.list(grid)
                .size(),
            "A rejected creation changes nothing");
        assertThrows(TooManyReadRequests.class, () -> editor.run(grid, first.key()));
        clock.addAndGet(INTERVAL - 1);
        assertThrows(TooManyReadRequests.class, () -> editor.run(grid, first.key()));
        clock.addAndGet(1);
        assertEquals(RunResult.QUEUED, editor.run(grid, first.key()));
        assertThrows(
            TooManyReadRequests.class,
            () -> editor.create(grid, WebPrincipal.admin(), order("Second", true, INGOT, INGOT), descriptions()));
        waitOutTheInterval();
        assertNotNull(editor.create(grid, WebPrincipal.admin(), order("Second", true, INGOT, INGOT), descriptions()));
    }

    @Test
    void networksHaveIndependentAllowances() throws Exception {
        StableKey other = TestGridFixtures.resolvedKey(new TestGridFixtures.TestGrid(88602, false, CONTROLLER_ONLINE));
        editor.create(grid, WebPrincipal.admin(), order("First", true, PLATE, PLATE), descriptions());
        assertNotNull(editor.create(other, WebPrincipal.admin(), order("Other", true, PLATE, PLATE), descriptions()));
    }

    @Test
    void anEditThatAddsNoResourceAndEnablesNothingNeverUsesTheAllowance() throws Exception {
        StandingOrderStore.Valid created = editor
            .create(grid, WebPrincipal.admin(), order("Keep", true, PLATE, PLATE), descriptions());
        for (String name : new String[] { "Renamed", "Renamed again", "And again" }) {
            assertTrue(replace(grid, created.key(), order(name, true, PLATE, PLATE), Collections.emptyMap()));
        }
        assertTrue(replace(grid, created.key(), order("Off", false, PLATE, PLATE), Collections.emptyMap()));
        assertTrue(replace(grid, created.key(), order("Narrower", false, PLATE, PLATE), Collections.emptyMap()));
        assertTrue(editor.delete(grid, created.key()), "Deleting is never limited");
        // The creation above is still inside its interval: nothing in between used or reset the allowance.
        assertThrows(
            TooManyReadRequests.class,
            () -> editor.create(grid, WebPrincipal.admin(), order("Again", true, PLATE, PLATE), descriptions()));
    }

    @Test
    void anEditThatAddsAResourceOrEnablesAnOrderUsesTheAllowance() throws Exception {
        StandingOrderStore.Valid created = editor
            .create(grid, WebPrincipal.admin(), order("Keep", false, PLATE, PLATE), descriptions());
        waitOutTheInterval();
        assertTrue(replace(grid, created.key(), order("Keep", false, PLATE, INGOT), descriptions()));
        assertThrows(
            TooManyReadRequests.class,
            () -> replace(grid, created.key(), order("Keep", false, DUST, INGOT), descriptions()),
            "A new trigger resource costs a read like a new crafted resource");
        waitOutTheInterval();
        assertTrue(replace(grid, created.key(), order("Keep", false, DUST, INGOT), descriptions()));
        assertThrows(
            TooManyReadRequests.class,
            () -> replace(grid, created.key(), order("Keep", true, DUST, INGOT), Collections.emptyMap()),
            "Enabling a disabled order may start the network's stock feed");
        waitOutTheInterval();
        assertTrue(replace(grid, created.key(), order("Keep", true, DUST, INGOT), Collections.emptyMap()));
        assertThrows(
            TooManyReadRequests.class,
            () -> replace(grid, created.key(), order("Keep", true, PLATE, INGOT), descriptions()));
        assertEquals(
            order("Keep", true, DUST, INGOT),
            ((StandingOrderStore.Valid) store.get(grid, created.key())).definition(),
            "A rejected edit changes nothing");
    }

    @Test
    void repairingAnOrderThatCannotBeReadTreatsEveryResourceAsNew() throws Exception {
        StandingOrderStore.Valid created = editor
            .create(grid, WebPrincipal.admin(), order("Keep", true, PLATE, PLATE), descriptions());
        waitOutTheInterval();
        execute("UPDATE standing_orders SET definition = '{\"broken\"' WHERE order_key = '" + created.key() + "'");
        assertInstanceOf(StandingOrderStore.Invalid.class, store.get(grid, created.key()));
        assertTrue(replace(grid, created.key(), order("Repaired", false, PLATE, PLATE), Collections.emptyMap()));
        assertThrows(
            TooManyReadRequests.class,
            () -> editor.create(grid, WebPrincipal.admin(), order("Another", true, INGOT, INGOT), descriptions()),
            "Nothing was known about the broken order's resources, so the repair used the allowance");
    }

    @Test
    void runningNeedsAnExistingValidEnabledOrder() throws Exception {
        StandingOrderStore.Valid enabled = editor
            .create(grid, WebPrincipal.admin(), order("On", true, PLATE, PLATE), descriptions());
        waitOutTheInterval();
        StandingOrderStore.Valid disabled = editor
            .create(grid, WebPrincipal.admin(), order("Off", false, INGOT, INGOT), descriptions());
        assertEquals(RunResult.NOT_FOUND, editor.run(grid, TestGridFixtures.key(5)));
        assertEquals(RunResult.NOT_RUNNABLE, editor.run(grid, disabled.key()));
        execute("UPDATE standing_orders SET definition = '{\"broken\"' WHERE order_key = '" + enabled.key() + "'");
        assertEquals(RunResult.NOT_RUNNABLE, editor.run(grid, enabled.key()));
        waitOutTheInterval();
        assertEquals(
            RunResult.NOT_FOUND,
            editor.run(TestGridFixtures.key(6), enabled.key()),
            "Orders belong to their network");
    }

    @Test
    void aRunThatIsNotAllowedDoesNotUseTheAllowance() throws Exception {
        StandingOrderStore.Valid disabled = editor
            .create(grid, WebPrincipal.admin(), order("Off", false, INGOT, INGOT), descriptions());
        waitOutTheInterval();
        assertEquals(RunResult.NOT_RUNNABLE, editor.run(grid, disabled.key()));
        assertTrue(replace(grid, disabled.key(), order("Off", false, INGOT, PLATE), descriptions()));
    }

    @Test
    void changesAreMutuallyExclusiveSoAStoreWriteAndItsPostCannotBeOvertaken() throws Exception {
        StandingOrderStore.Valid existing = editor
            .create(grid, WebPrincipal.admin(), order("Existing", true, PLATE, PLATE), descriptions());
        waitOutTheInterval();
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            blockCreation.set(true);
            Future<StandingOrderStore.Valid> creation = threads.submit(
                () -> editor.create(grid, WebPrincipal.admin(), order("Slow", true, INGOT, INGOT), descriptions()));
            assertTrue(creationEntered.await(10, SECONDS));
            // The creation sits inside its store write. Another change must not even start, not merely wait for the
            // world database: a deletion posted before the creation's own post could otherwise be overtaken.
            Future<Boolean> deletion = threads.submit(() -> editor.delete(grid, existing.key()));
            Future<RunResult> run = threads.submit(() -> editor.run(grid, TestGridFixtures.key(5)));
            assertThrows(TimeoutException.class, () -> deletion.get(300, MILLISECONDS));
            assertThrows(TimeoutException.class, () -> run.get(300, MILLISECONDS));
            assertNotNull(store.get(grid, existing.key()), "The deletion has not run yet");
            creationMayFinish.countDown();
            assertNotNull(creation.get(10, SECONDS));
            assertTrue(deletion.get(10, SECONDS));
            assertEquals(RunResult.NOT_FOUND, run.get(10, SECONDS));
        } finally {
            creationMayFinish.countDown();
            threads.shutdownNow();
        }
    }
}
