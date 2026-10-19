package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.cpu.CpuLiveCoordinator;
import pl.kuba6000.ae2webintegration.core.cpu.CpuRegistry;
import pl.kuba6000.ae2webintegration.core.cpu.CpuTopic;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.live.LiveStreams;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

/** Live CPU view through the coordinator, registry and SSE transport, with an explicit server clock. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CpuLiveStreamsHttpTest extends GridTestScope {

    private static final long RECONCILIATION_MILLIS = 5000;

    private final CpuFixtures.Resource iron = new CpuFixtures.Resource("iron");
    private final CpuFixtures.Resource gear = new CpuFixtures.Resource("gear");
    private final CpuFixtures.Resource tin = new CpuFixtures.Resource("tin");
    private final CpuFixtures.Resource exotic = new CpuFixtures.Resource("exotic", false);
    private final CpuFixtures.PausableCpu cpu = new CpuFixtures.PausableCpu(1);
    private final CpuFixtures.Cpu other = new CpuFixtures.Cpu(2);
    private final CpuFixtures.Grid nativeGrid = new CpuFixtures.Grid(9201, cpu, other);
    private final LiveHub hub = new LiveHub();
    private final CpuRegistry registry = new CpuRegistry(hub);
    private final ConfigSettings.ResourceTracking settings = new ConfigSettings.ResourceTracking();
    private final CpuLiveCoordinator coordinator = new CpuLiveCoordinator(
        registry,
        CoreEngine.GRID_IDENTITIES,
        settings);
    private final ExecutorService ordinary = Executors.newSingleThreadExecutor();
    private LiveStreams streams;
    private HttpServer server;
    private IAE previousAe;
    private StableKey gridKey;
    private AuthSession session;
    private long clock;

    @BeforeEach
    void start() throws Exception {
        settings.activeIntervalMillis = 50;
        previousAe = AE2Controller.AE2Interface;
        AE2Controller.AE2Interface = new CpuFixtures.Ae(nativeGrid);
        gridKey = TestGridFixtures.resolvedKey(nativeGrid);
        session = new AuthSession(WebPrincipal.admin(), Long.MAX_VALUE);
        cpu.busy = true;
        other.busy = true;
        cpu.output = gear;
        clock = System.currentTimeMillis();
        streams = new LiveStreams(hub, CoreEngine.GRID_IDENTITIES, null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(ordinary);
        server.createContext("/events/", exchange -> {
            String cpuKey = exchange.getRequestURI()
                .getRawPath()
                .substring("/events/".length());
            streams.open(
                new AE2Controller.RequestContext(exchange, session),
                CpuTopic.keyOf(gridKey, StableKey.parse(cpuKey)),
                exchange.getRequestURI()
                    .getRawQuery(),
                false);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        streams.close();
        server.stop(0);
        ordinary.shutdownNow();
        AE2Controller.AE2Interface = previousAe;
        AE2JobTracker.clearActiveJobs();
    }

    private void tick(long advanceMillis) {
        clock += advanceMillis;
        coordinator.tick(clock);
    }

    private CpuRegistry.Bootstrap view(CpuFixtures.Cpu target) {
        return view(target, session);
    }

    private CpuRegistry.Bootstrap view(CpuFixtures.Cpu target, AuthSession login) {
        coordinator.prepareForViewer(gridKey, nativeGrid, target.key, target, clock);
        CpuRegistry.Bootstrap bootstrap = registry
            .reserve(gridKey, target.key, login, false, null, null, System.currentTimeMillis());
        assertNotNull(bootstrap);
        return bootstrap;
    }

    private Connection open(CpuRegistry.Bootstrap bootstrap, CpuFixtures.Cpu target) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + server.getAddress()
                .getPort() + "/events/" + target.key + "?" + bootstrap.subscription())
            .openConnection();
        connection.setReadTimeout(5000);
        Connection stream = new Connection(connection);
        assertEquals(
            "ready",
            CpuFixtures.readEvent(stream.input)
                .name());
        return stream;
    }

    private JsonObject delta(Connection stream) throws Exception {
        CpuFixtures.Event event = CpuFixtures.readEvent(stream.input);
        assertEquals("delta", event.name());
        return event.data();
    }

    private static JsonObject update(JsonObject delta, CpuFixtures.Resource resource) {
        for (JsonElement update : delta.getAsJsonArray("updates")) {
            if (update.getAsJsonObject()
                .get("itemKey")
                .getAsString()
                .equals(
                    resource.key()
                        .toString()))
                return update.getAsJsonObject();
        }
        return fail("No update for " + resource.name);
    }

    private static void assertKeys(JsonArray array, CpuFixtures.Resource... resources) {
        assertEquals(resources.length, array.size());
        for (int index = 0; index < resources.length; index++) {
            JsonElement element = array.get(index);
            assertEquals(
                resources[index].key()
                    .toString(),
                element.isJsonObject() ? element.getAsJsonObject()
                    .get("itemKey")
                    .getAsString() : element.getAsString());
        }
    }

    private void track(CpuFixtures.Resource waitingFor) {
        TestGridFixtures.track(nativeGrid);
        AE2JobTracker.addJob(cpu, nativeGrid, false);
        cpu.waiting.amounts.put(waitingFor, 5L);
        AE2JobTracker.updateCraftingStatus(cpu, waitingFor);
    }

    @Test
    void snapshotHasAViewAndTheSharedBaselineOfRowsDescriptionsAndHeader() {
        cpu.set(iron, 4, 2, 1)
            .set(exotic, 7, 0, 0);
        cpu.selectionMode = pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode.ALL;

        CpuRegistry.Bootstrap bootstrap = view(cpu);

        assertNotNull(bootstrap.view());
        assertEquals(
            36,
            bootstrap.subscription()
                .length());
        assertEquals(
            1,
            bootstrap.rows()
                .size());
        assertEquals(
            4,
            bootstrap.rows()
                .get(0)
                .amounts()
                .active());
        assertEquals(
            "Display iron",
            bootstrap.descriptions()
                .get(iron.key())
                .displayName());
        assertEquals(
            1,
            bootstrap.unkeyed()
                .size());
        assertTrue(
            bootstrap.header()
                .busy());
    }

    @Test
    void changeBetweenSnapshotAndOpenIsDeliveredToTheOpeningStream() throws Exception {
        cpu.set(iron, 4, 0, 0);
        CpuRegistry.Bootstrap bootstrap = view(cpu);

        cpu.set(iron, 3, 1, 0);
        coordinator.dirty(cpu, iron);
        tick(100);

        try (Connection stream = open(bootstrap, cpu)) {
            JsonObject delta = delta(stream);
            JsonObject row = update(delta, iron);
            assertEquals(
                3,
                row.get("active")
                    .getAsLong());
            assertEquals(
                1,
                row.get("pending")
                    .getAsLong());
        }
    }

    @Test
    void dirtyKeyDeliversOnlyTheChangedRowWithAbsoluteValues() throws Exception {
        cpu.set(iron, 4, 0, 0)
            .set(gear, 1, 0, 0);
        try (Connection stream = open(view(cpu), cpu)) {
            cpu.set(iron, 3, 1, 2);
            coordinator.dirty(cpu, iron);
            tick(100);

            JsonObject delta = delta(stream);

            assertKeys(delta.getAsJsonArray("updates"), iron);
            JsonObject row = update(delta, iron);
            assertEquals(
                3,
                row.get("active")
                    .getAsLong());
            assertEquals(
                1,
                row.get("pending")
                    .getAsLong());
            assertEquals(
                2,
                row.get("stored")
                    .getAsLong());
            assertFalse(
                row.get("crafting")
                    .getAsBoolean());
            assertEquals(
                0,
                delta.getAsJsonArray("definitions")
                    .size());
            assertEquals(
                0,
                delta.getAsJsonArray("removed")
                    .size());
            assertTrue(
                delta.get("cpu")
                    .isJsonNull());
            assertTrue(
                delta.get("unkeyed")
                    .isJsonNull());
            assertNotNull(delta.getAsJsonObject("view"));
        }
    }

    @Test
    void marksThatChangeNothingAndClockProgressNeverProduceAnUpdate() throws Exception {
        cpu.set(iron, 4, 0, 0)
            .set(gear, 1, 0, 0);
        track(iron);
        try (Connection stream = open(view(cpu), cpu)) {
            coordinator.dirty(cpu, iron);
            tick(100);
            tick(RECONCILIATION_MILLIS + 100);
            coordinator.dirty(cpu, iron);
            tick(RECONCILIATION_MILLIS + 100);
            cpu.set(gear, 9, 0, 0);
            coordinator.dirty(cpu, gear);
            tick(100);

            JsonObject delta = delta(stream);

            assertKeys(delta.getAsJsonArray("updates"), gear);
            assertTrue(
                delta.get("cpu")
                    .isJsonNull());
        }
    }

    @Test
    void runningMeasurementIsDeliveredWhenTheJobStartsOrStopsWaitingAndWhenItsCountsChange() throws Exception {
        cpu.set(iron, 4, 0, 0);
        TestGridFixtures.track(nativeGrid);
        AE2JobTracker.addJob(cpu, nativeGrid, false);
        try (Connection stream = open(view(cpu), cpu)) {
            cpu.waiting.amounts.put(iron, 5L);
            AE2JobTracker.updateCraftingStatus(cpu, iron);
            coordinator.dirty(cpu, iron);
            tick(100);
            JsonObject started = update(delta(stream), iron);
            assertTrue(
                started.get("crafting")
                    .getAsBoolean());

            cpu.waiting.amounts.put(iron, 3L);
            AE2JobTracker.updateCraftingStatus(cpu, iron);
            coordinator.dirty(cpu, iron);
            tick(100);
            JsonObject progressed = update(delta(stream), iron);
            assertEquals(
                2,
                progressed.get("craftedTotal")
                    .getAsLong());

            cpu.waiting.amounts.remove(iron);
            AE2JobTracker.updateCraftingStatus(cpu, iron);
            coordinator.dirty(cpu, iron);
            tick(100);
            JsonObject stopped = update(delta(stream), iron);
            assertFalse(
                stopped.get("crafting")
                    .getAsBoolean());
            assertEquals(
                5,
                stopped.get("craftedTotal")
                    .getAsLong());
        }
    }

    @Test
    void newResourceCarriesItsDefinitionOnceAndRemovalIsFollowedByANewDefinitionWhenItReturns() throws Exception {
        cpu.set(iron, 4, 0, 0);
        try (Connection stream = open(view(cpu), cpu)) {
            cpu.set(tin, 2, 0, 0);
            coordinator.dirty(cpu, tin);
            tick(100);
            JsonObject added = delta(stream);
            assertKeys(added.getAsJsonArray("definitions"), tin);
            assertEquals(
                "Display tin",
                added.getAsJsonArray("definitions")
                    .get(0)
                    .getAsJsonObject()
                    .get("displayName")
                    .getAsString());

            cpu.set(tin, 1, 0, 0);
            coordinator.dirty(cpu, tin);
            tick(100);
            JsonObject changed = delta(stream);
            assertKeys(changed.getAsJsonArray("updates"), tin);
            assertEquals(
                0,
                changed.getAsJsonArray("definitions")
                    .size());

            cpu.set(tin, 0, 0, 0);
            coordinator.dirty(cpu, tin);
            tick(100);
            JsonObject removed = delta(stream);
            assertKeys(removed.getAsJsonArray("removed"), tin);
            assertEquals(
                0,
                removed.getAsJsonArray("updates")
                    .size());

            cpu.set(tin, 1, 0, 0);
            coordinator.dirty(cpu, tin);
            tick(100);
            assertKeys(delta(stream).getAsJsonArray("definitions"), tin);
        }
    }

    @Test
    void unkeyedResourcesAreReplacedAsAWholeOnlyWhenTheyChange() throws Exception {
        cpu.set(iron, 4, 0, 0)
            .set(exotic, 7, 0, 0);
        try (Connection stream = open(view(cpu), cpu)) {
            cpu.set(iron, 3, 0, 0);
            coordinator.dirty(cpu, iron);
            tick(100);
            assertTrue(
                delta(stream).get("unkeyed")
                    .isJsonNull());

            cpu.set(exotic, 8, 0, 0);
            coordinator.dirty(cpu, exotic);
            tick(100);
            JsonObject replaced = delta(stream);
            JsonArray unkeyed = replaced.getAsJsonArray("unkeyed");
            assertEquals(1, unkeyed.size());
            assertEquals(
                8,
                unkeyed.get(0)
                    .getAsJsonObject()
                    .get("active")
                    .getAsLong());
            assertEquals(
                0,
                replaced.getAsJsonArray("updates")
                    .size());

            cpu.set(exotic, 0, 0, 0);
            coordinator.dirty(cpu, exotic);
            tick(100);
            assertEquals(
                0,
                delta(stream).getAsJsonArray("unkeyed")
                    .size());
        }
    }

    @Test
    void dirtyReadPublishesRemainingOutputAndBusyChangesWithoutWaitingForReconciliation() throws Exception {
        cpu.set(iron, 4, 0, 0);
        cpu.outputAmount = 8;
        try (Connection stream = open(view(cpu), cpu)) {
            cpu.outputAmount = 6;
            coordinator.dirty(cpu, gear);
            tick(100);
            JsonObject changed = delta(stream);
            assertEquals(
                6,
                changed.getAsJsonObject("cpu")
                    .getAsJsonObject("finalOutput")
                    .get("quantity")
                    .getAsLong());
            assertEquals(
                0,
                changed.getAsJsonArray("updates")
                    .size());

            cpu.set(iron, 3, 0, 0);
            coordinator.dirty(cpu, iron);
            tick(100);
            assertTrue(
                delta(stream).get("cpu")
                    .isJsonNull(),
                "An unchanged header is omitted from a row delta");

            cpu.busy = false;
            coordinator.dirty(cpu, gear);
            tick(100);
            JsonObject idle = delta(stream);
            assertFalse(
                idle.getAsJsonObject("cpu")
                    .get("isBusy")
                    .getAsBoolean());
            assertKeys(idle.getAsJsonArray("removed"), iron);
        }
    }

    @Test
    void pauseIsFoundByReconciliationAsAHeaderChange() throws Exception {
        cpu.set(iron, 4, 0, 0);
        try (Connection stream = open(view(cpu), cpu)) {
            cpu.paused = true;
            tick(100);
            tick(RECONCILIATION_MILLIS);

            JsonObject delta = delta(stream);

            assertTrue(
                delta.getAsJsonObject("cpu")
                    .get("isPaused")
                    .getAsBoolean());
            assertEquals(
                0,
                delta.getAsJsonArray("updates")
                    .size());
            assertFalse(
                delta.getAsJsonObject("cpu")
                    .has("items")
                    && !delta.getAsJsonObject("cpu")
                        .get("items")
                        .isJsonNull());
        }
    }

    @Test
    void requestedFullReadRunsOnTheNextTickAndIsConsumedOnce() throws Exception {
        cpu.set(iron, 4, 0, 0);
        coordinator.requestFullRead(other);
        try (Connection stream = open(view(cpu), cpu)) {
            int reads = cpu.bulkReads;
            cpu.paused = true;
            cpu.set(tin, 0, 0, 9);
            coordinator.requestFullRead(cpu);
            tick(1);
            JsonObject paused = delta(stream);
            assertTrue(
                paused.getAsJsonObject("cpu")
                    .get("isPaused")
                    .getAsBoolean());
            assertKeys(paused.getAsJsonArray("definitions"), tin);
            assertEquals(reads + 1, cpu.bulkReads);
            tick(1);
            assertEquals(reads + 1, cpu.bulkReads);
            assertEquals(0, other.bulkReads);

            cpu.paused = false;
            coordinator.requestFullRead(cpu);
            tick(1);
            assertFalse(
                delta(stream).getAsJsonObject("cpu")
                    .get("isPaused")
                    .getAsBoolean());
        }
    }

    @Test
    void reconciliationFindsRemovedResourcesThatSentNoNotification() throws Exception {
        cpu.set(iron, 4, 0, 0)
            .set(gear, 1, 0, 0);
        try (Connection stream = open(view(cpu), cpu)) {
            cpu.set(gear, 0, 0, 0);
            tick(RECONCILIATION_MILLIS + 100);

            assertKeys(delta(stream).getAsJsonArray("removed"), gear);
        }
    }

    @Test
    void jobLifecycleInvalidatesTheViewAndAFreshBaselineStartsANewOne() throws Exception {
        cpu.set(iron, 4, 0, 0);
        try (Connection stream = open(view(cpu), cpu)) {
            coordinator.jobChanged(cpu);
            assertEquals(
                "invalidate",
                CpuFixtures.readEvent(stream.input)
                    .name());
        }
        tick(100);
        cpu.set(iron, 1, 0, 0);
        CpuRegistry.Bootstrap fresh = view(cpu);
        assertEquals(
            1,
            fresh.rows()
                .get(0)
                .amounts()
                .active());
        try (Connection stream = open(fresh, cpu)) {
            cpu.set(iron, 2, 0, 0);
            coordinator.dirty(cpu, iron);
            tick(100);
            assertEquals(
                2,
                update(delta(stream), iron).get("active")
                    .getAsLong());
        }
    }

    @Test
    void cpuRemovedFromTheGridOrRebuiltAsANewObjectInvalidatesTheView() throws Exception {
        cpu.set(iron, 4, 0, 0);
        other.set(gear, 1, 0, 0);
        try (Connection removed = open(view(cpu), cpu); Connection rebuilt = open(view(other), other)) {
            nativeGrid.cpus.remove(cpu);
            nativeGrid.cpus.remove(other);
            CpuFixtures.Cpu replacement = new CpuFixtures.Cpu(2);
            nativeGrid.cpus.add(replacement);
            tick(RECONCILIATION_MILLIS + 100);
            tick(100);

            assertEquals(
                "invalidate",
                CpuFixtures.readEvent(removed.input)
                    .name());
            assertEquals(
                "invalidate",
                CpuFixtures.readEvent(rebuilt.input)
                    .name());
        }
    }

    @Test
    void retiringTheOldBindingOfASurvivingCpuKeepsTheNewBindingsNotifications() throws Exception {
        cpu.set(iron, 4, 0, 0);
        CpuFixtures.Grid movedGrid = new CpuFixtures.Grid(9202, cpu);
        StableKey movedKey = TestGridFixtures.resolvedKey(movedGrid);
        CpuRegistry.Bootstrap old = view(cpu);
        coordinator.prepareForViewer(movedKey, movedGrid, cpu.key, cpu, clock);
        CpuRegistry.Bootstrap moved = registry
            .reserve(movedKey, cpu.key, session, false, null, null, System.currentTimeMillis());
        assertNotNull(moved);
        registry.cancel(old.subscription());
        tick(100);
        gridKey = movedKey;
        try (Connection stream = open(moved, cpu)) {
            cpu.set(iron, 3, 0, 0);
            coordinator.dirty(cpu, iron);
            tick(100);

            assertEquals(
                3,
                update(delta(stream), iron).get("active")
                    .getAsLong());
        }
    }

    @Test
    void goneGridEndsTheViewAndReleasesTheCpu() throws Exception {
        cpu.set(iron, 4, 0, 0);
        try (Connection stream = open(view(cpu), cpu)) {
            CoreEngine.GRID_IDENTITIES.clear();
            assertEquals(
                "invalidate",
                CpuFixtures.readEvent(stream.input)
                    .name());
        }
        tick(100);
        int reads = cpu.bulkReads;
        coordinator.dirty(cpu, iron);
        tick(RECONCILIATION_MILLIS + 100);
        assertEquals(reads, cpu.bulkReads);
    }

    @Test
    void revokedPermissionEndsEveryViewer() throws Exception {
        AuthSession owner = new AuthSession(TestGridFixtures.principal(TestGridFixtures.OWNER_ID), Long.MAX_VALUE);
        cpu.set(iron, 4, 0, 0);
        session = owner;
        try (Connection first = open(view(cpu, owner), cpu); Connection second = open(view(cpu, owner), cpu)) {
            nativeGrid.withoutSources();
            assertEquals(
                "revoked",
                CpuFixtures.readEvent(first.input)
                    .name());
            assertEquals(
                "revoked",
                CpuFixtures.readEvent(second.input)
                    .name());
        }
    }

    @Test
    void viewersShareOneNativeReadAndEachReceivesTheUpdate() throws Exception {
        cpu.set(iron, 4, 0, 0);
        try (Connection first = open(view(cpu), cpu); Connection second = open(view(cpu), cpu)) {
            int reads = cpu.bulkReads;
            cpu.set(iron, 3, 0, 0);
            coordinator.dirty(cpu, iron);
            tick(100);

            assertEquals(
                3,
                update(delta(first), iron).get("active")
                    .getAsLong());
            assertEquals(
                3,
                update(delta(second), iron).get("active")
                    .getAsLong());
            assertEquals(reads + 1, cpu.bulkReads, "One restricted read serves every viewer");
        }
    }

    @Test
    void anotherViewersBaselineReadPublishesItsChangesToTheViewersAlreadyStreaming() throws Exception {
        cpu.set(iron, 4, 0, 0);
        try (Connection first = open(view(cpu), cpu)) {
            cpu.paused = true;
            CpuRegistry.Bootstrap second = view(cpu);
            assertTrue(
                second.header()
                    .paused());
            assertTrue(
                delta(first).getAsJsonObject("cpu")
                    .get("isPaused")
                    .getAsBoolean());
        }
    }

    @Test
    void cpusOfOneGridHaveIndependentViews() throws Exception {
        cpu.set(iron, 4, 0, 0);
        other.set(gear, 1, 0, 0);
        try (Connection first = open(view(cpu), cpu); Connection second = open(view(other), other)) {
            other.set(gear, 2, 0, 0);
            coordinator.dirty(other, gear);
            cpu.set(iron, 5, 0, 0);
            coordinator.dirty(cpu, iron);
            tick(100);
            tick(100);

            assertKeys(delta(first).getAsJsonArray("updates"), iron);
            assertKeys(delta(second).getAsJsonArray("updates"), gear);
        }
    }

    @Test
    void unviewedCpusAndReleasedViewsNeverReadAndTheirStateIsGone() {
        cpu.set(iron, 4, 0, 0);
        coordinator.dirty(cpu, iron);
        tick(100);
        tick(RECONCILIATION_MILLIS + 100);
        assertEquals(0, cpu.bulkReads, "A CPU nobody views is never read");

        CpuRegistry.Bootstrap bootstrap = view(cpu);
        registry.cancel(bootstrap.subscription());
        tick(100);
        int reads = cpu.bulkReads;
        coordinator.dirty(cpu, iron);
        tick(100);
        tick(RECONCILIATION_MILLIS + 100);
        assertEquals(reads, cpu.bulkReads);
        assertNull(registry.reserve(gridKey, cpu.key, session, false, null, null, System.currentTimeMillis()));
    }

    private static final class Connection implements AutoCloseable {

        private final HttpURLConnection connection;
        private final BufferedReader input;

        private Connection(HttpURLConnection connection) throws Exception {
            this.connection = connection;
            input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws Exception {
            input.close();
            connection.disconnect();
        }
    }
}
