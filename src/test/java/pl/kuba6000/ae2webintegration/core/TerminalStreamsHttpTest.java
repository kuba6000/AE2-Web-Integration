package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalStreams;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentTrackingService;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservationFixtures;

class TerminalStreamsHttpTest extends GridTestScope {

    private TestGridFixtures.TestGrid nativeGrid;

    @Test
    void reservedTerminalReceivesAsynchronousRecentDataAfterAnEmptyBootstrap() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        List<StorageObservationFixtures.Row> rows = Collections.singletonList(row(iron, "Iron", 1));
        try (Fixture fixture = new Fixture(rows);
            RecentTrackingService recent = new RecentTrackingService(fixture.state, 100)) {
            recent.open(fixture.grid, 5, false, true, 100, 1000);
            var bootstrap = fixture.state
                .reserve(fixture.grid, fixture.session, false, null, System.currentTimeMillis());
            assertNotNull(bootstrap);
            assertNull(bootstrap.recent());
            try (Connection connection = fixture.open(bootstrap.subscription())) {
                assertEquals("ready", readEvent(connection.input).name());
                assertTrue(recent.refresh(fixture.grid, 5, 1000));
                JsonObject delta = readEvent(connection.input).data();
                assertEquals(
                    0,
                    delta.getAsJsonArray("updates")
                        .size());
                assertEquals(
                    "0",
                    delta.getAsJsonObject("recent")
                        .getAsJsonObject("resources")
                        .getAsJsonObject(iron.toString())
                        .get("input")
                        .getAsString());
            }
        }
    }

    @Test
    void unchangedStockSamplePublishesItsMeasurementTimeWithoutResourceUpdates() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        List<StorageObservationFixtures.Row> rows = Collections.singletonList(row(iron, "Iron", 1));
        try (Fixture fixture = new Fixture(rows)) {
            RecentResourceActivity recent = new RecentResourceActivity(true, false, 100, 1000);
            recent.observe(
                StorageObservationFixtures.observation(1000, true, true, rows, Collections.emptyList()),
                true,
                false);
            recent.observe(
                StorageObservationFixtures.observation(301000, true, true, rows, Collections.emptyList()),
                true,
                false);
            fixture.state.recent(fixture.grid, 5, fixture.state.recentToken(fixture.grid, 5), recent.snapshot());
            try (Connection connection = fixture.open(fixture.reserve())) {
                assertEquals("ready", readEvent(connection.input).name());
                recent.observe(
                    StorageObservationFixtures.observation(601000, true, true, rows, Collections.emptyList()),
                    true,
                    false);
                fixture.state.recent(fixture.grid, 5, fixture.state.recentToken(fixture.grid, 5), recent.snapshot());
                JsonObject delta = readEvent(connection.input).data();
                assertEquals(
                    0,
                    delta.getAsJsonArray("updates")
                        .size());
                assertEquals(
                    0,
                    delta.getAsJsonArray("definitions")
                        .size());
                JsonObject summary = delta.getAsJsonObject("recent");
                assertEquals(
                    601000,
                    summary.get("stockObservedAt")
                        .getAsLong());
                assertEquals(
                    1000,
                    summary.get("stockSince")
                        .getAsLong());
            }
        }
    }

    @Test
    void reappearingResourceReceivesItsRecentSummaryEvenWhenItsTotalsDidNotChange() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        List<StorageObservationFixtures.Row> rows = Collections.singletonList(row(iron, "Iron", 1));
        try (Fixture fixture = new Fixture(rows)) {
            RecentResourceActivity recent = new RecentResourceActivity(true, true, 100, 1000);
            recent.observe(observation(1000, rows), true, true);
            fixture.state.recent(fixture.grid, 5, fixture.state.recentToken(fixture.grid, 5), recent.snapshot());
            try (Connection connection = fixture.open(fixture.reserve())) {
                assertEquals("ready", readEvent(connection.input).name());
                fixture.publish(2000, Collections.emptyList());
                assertEquals(
                    1,
                    readEvent(connection.input).data()
                        .getAsJsonArray("removed")
                        .size());
                fixture.publish(3000, rows);
                JsonObject delta = readEvent(connection.input).data();
                assertEquals(
                    1,
                    delta.getAsJsonArray("definitions")
                        .size());
                assertEquals(
                    "0",
                    delta.getAsJsonObject("recent")
                        .getAsJsonObject("resources")
                        .getAsJsonObject(iron.toString())
                        .get("input")
                        .getAsString());
            }
        }
    }

    @Test
    void recentFlowAndExpiryReachStreamWithoutRepeatingQuantitiesOrDefinitions() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        List<StorageObservationFixtures.Row> rows = Collections.singletonList(row(iron, "Iron", 1));
        try (Fixture fixture = new Fixture(rows)) {
            RecentResourceActivity recent = new RecentResourceActivity(true, true, 100, 1000);
            recent.observe(observation(1000, rows), true, true);
            fixture.state.recent(fixture.grid, 5, fixture.state.recentToken(fixture.grid, 5), recent.snapshot());
            TerminalRegistry.Bootstrap bootstrap = fixture.state
                .reserve(fixture.grid, fixture.session, false, null, System.currentTimeMillis());
            assertNotNull(bootstrap);
            var initialRecent = bootstrap.recent();
            assertNotNull(initialRecent);
            assertEquals(
                "0",
                initialRecent.resources()
                    .get(iron)
                    .input());
            try (Connection connection = fixture.open(bootstrap.subscription())) {
                assertEquals("ready", readEvent(connection.input).name());
                recent.observe(
                    new StorageObservation(
                        6000,
                        false,
                        true,
                        false,
                        Collections.emptyList(),
                        Collections.singletonList(new StorageObservation.Flow(iron, Long.MAX_VALUE, 100)),
                        Collections.emptyMap(),
                        Collections.emptyList()),
                    false,
                    true);
                fixture.state.recent(fixture.grid, 5, fixture.state.recentToken(fixture.grid, 5), recent.snapshot());
                JsonObject delta = readEvent(connection.input).data();
                assertEquals(
                    0,
                    delta.getAsJsonArray("updates")
                        .size());
                assertEquals(
                    0,
                    delta.getAsJsonArray("definitions")
                        .size());
                JsonObject amount = delta.getAsJsonObject("recent")
                    .getAsJsonObject("resources")
                    .getAsJsonObject(iron.toString());
                assertTrue(
                    amount.get("input")
                        .getAsJsonPrimitive()
                        .isString());
                assertEquals(
                    "9223372036854775807",
                    amount.get("input")
                        .getAsString());
                recent.advance(131000);
                assertEquals(
                    6000,
                    recent.snapshot()
                        .flowObservedAt(),
                    "Expiry is not a new flow measurement");
                fixture.state.recent(fixture.grid, 5, fixture.state.recentToken(fixture.grid, 5), recent.snapshot());
                delta = readEvent(connection.input).data();
                assertEquals(
                    0,
                    delta.getAsJsonArray("updates")
                        .size());
                assertEquals(
                    "0",
                    delta.getAsJsonObject("recent")
                        .getAsJsonObject("resources")
                        .getAsJsonObject(iron.toString())
                        .get("input")
                        .getAsString());
            }
        }
    }

    @Test
    void loginExpiryClosesIdleStreamWithoutRequestsOrServerTicks() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            AuthSession shortLogin = new AuthSession(fixture.principal, System.currentTimeMillis() + 600);
            fixture.requestSession.set(shortLogin);
            TerminalRegistry.Bootstrap snapshot = fixture.state
                .reserve(fixture.grid, shortLogin, false, null, System.currentTimeMillis());
            assertNotNull(snapshot);
            try (Connection connection = fixture.open(snapshot.subscription())) {
                assertEquals("ready", readEvent(connection.input).name());
                assertEquals("revoked", readEvent(connection.input).name());
                assertNull(connection.input.readLine());
            }
        }
    }

    @Test
    void httpResetRejectsOldReservationsButPreservesLoginForNewSnapshot() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList());
            Connection connection = fixture.open(fixture.reserve())) {
            String unopened = fixture.reserve();
            assertEquals("ready", readEvent(connection.input).name());
            fixture.streams.reset();
            try {
                while (connection.input.readLine() != null) {}
            } catch (java.io.IOException closedConnection) {
                // Reset may abort an in-flight HTTP chunk.
            }
            assertEquals(400, fixture.status(unopened));
            fixture.state.activate(fixture.grid, 5, System.currentTimeMillis());
            fixture.publish(2, Collections.emptyList());
            try (Connection reopened = fixture.open(fixture.reserve())) {
                assertEquals("ready", readEvent(reopened.input).name());
            }
        }
    }

    @Test
    void reservationBelongsToExactLoginAndLogoutDoesNotAffectOtherLogin() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            String first = fixture.reserve();
            AuthSession other = new AuthSession(fixture.principal, Long.MAX_VALUE);
            fixture.requestSession.set(other);
            assertEquals(400, fixture.status(first));
            TerminalRegistry.Bootstrap second = fixture.state
                .reserve(fixture.grid, other, false, null, System.currentTimeMillis());
            assertNotNull(second);
            fixture.session.invalidate();
            assertEquals(400, fixture.status(first));
            try (Connection connection = fixture.open(second.subscription())) {
                assertEquals("ready", readEvent(connection.input).name());
            }
        }
    }

    @Test
    void changeAndRemovalDuringBlockedSendRemainPending() throws Exception {
        nativeGrid = TestGridFixtures.grid(32);
        StableKey grid = TestGridFixtures.resolvedKey(nativeGrid);
        AuthSession session = new AuthSession(WebPrincipal.admin(), Long.MAX_VALUE);
        StableKey iron = TestGridFixtures.key(51);
        TerminalRegistry state = new TerminalRegistry();
        state.activate(grid, 5, System.currentTimeMillis());
        state.observed(grid, 5, observation(1, Collections.singletonList(row(iron, "Iron", 1))));
        TerminalRegistry.Bootstrap bootstrap = state.reserve(grid, session, false, null, System.currentTimeMillis());
        assertNotNull(bootstrap);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch sent = new CountDownLatch(2);
        List<String> deltas = Collections.synchronizedList(new ArrayList<>());
        java.io.OutputStream output = new java.io.OutputStream() {

            @Override
            public void write(int value) {
                throw new AssertionError("Expected a complete encoded event");
            }

            @Override
            public void write(byte @NotNull [] bytes, int offset, int length) throws java.io.IOException {
                String frame = new String(bytes, offset, length, StandardCharsets.UTF_8);
                if (!frame.startsWith("event: delta")) return;
                if (deltas.isEmpty()) {
                    writing.countDown();
                    try {
                        if (!release.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("Writer not released");
                    } catch (InterruptedException interrupted) {
                        throw new java.io.IOException(interrupted);
                    }
                }
                deltas.add(frame);
                sent.countDown();
            }
        };
        TestGridFixtures.TestExchange exchange = new TestGridFixtures.TestExchange(null) {

            @Override
            public java.io.InputStream getRequestBody() {
                return new java.io.ByteArrayInputStream(new byte[0]);
            }

            @Override
            public java.io.OutputStream getResponseBody() {
                return output;
            }
        };
        try (TerminalStreams streams = new TerminalStreams(state, CoreEngine.GRID_IDENTITIES, null)) {
            streams.open(new AE2Controller.RequestContext(exchange, session), grid, bootstrap.subscription(), false);
            state.observed(grid, 5, observation(2, Collections.singletonList(row(iron, "Iron", 10))));
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            state.observed(grid, 5, observation(3, Collections.singletonList(row(iron, "Iron", 20))));
            state.observed(grid, 5, observation(4, Collections.emptyList()));
            release.countDown();
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            Event first = readEvent(new BufferedReader(new java.io.StringReader(deltas.get(0))));
            Event second = readEvent(new BufferedReader(new java.io.StringReader(deltas.get(1))));
            assertEquals(
                10,
                first.data()
                    .getAsJsonArray("updates")
                    .get(0)
                    .getAsJsonObject()
                    .get("quantity")
                    .getAsLong());
            assertEquals(
                iron.toString(),
                second.data()
                    .getAsJsonArray("removed")
                    .get(0)
                    .getAsString());
        } finally {
            release.countDown();
        }
    }

    @Test
    void changedDescriptionAndReappearanceSendDefinitionsAgain() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        try (Fixture fixture = new Fixture(Collections.singletonList(row(iron, "Iron", 1)));
            Connection connection = fixture.open(fixture.reserve())) {
            assertEquals("ready", readEvent(connection.input).name());
            fixture.publish(2, Collections.singletonList(row(iron, "Renamed iron", 1)));
            JsonObject renamed = readEvent(connection.input).data();
            assertEquals(
                "Renamed iron",
                renamed.getAsJsonArray("definitions")
                    .get(0)
                    .getAsJsonObject()
                    .get("displayName")
                    .getAsString());
            fixture.publish(3, Collections.emptyList());
            assertEquals(
                1,
                readEvent(connection.input).data()
                    .getAsJsonArray("removed")
                    .size());
            fixture.publish(4, Collections.singletonList(row(iron, "Renamed iron", 2)));
            assertEquals(
                1,
                readEvent(connection.input).data()
                    .getAsJsonArray("definitions")
                    .size());
        }
    }

    @Test
    void tooManyPendingKeysRequiresFreshSnapshotRatherThanAnUnboundedQueue() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            String token = fixture.reserve();
            List<StorageObservationFixtures.Row> rows = new ArrayList<>();
            for (int i = 0; i < 17_000; i++) rows.add(row(StableKey.random(), "Resource", 1));
            fixture.publish(2, rows);
            assertEquals(400, fixture.status(token));
            assertNotNull(fixture.reserve());
        }
    }

    @Test
    void quantityOnlyChangesDoNotRepeatDefinitionsOrIcons() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        try (Fixture fixture = new Fixture(Collections.singletonList(row(iron, "Iron", 1)));
            Connection connection = fixture.open(fixture.reserve())) {
            assertEquals("ready", readEvent(connection.input).name());
            fixture.publish(2, Collections.singletonList(row(iron, "Iron", 20)));
            JsonObject update = readEvent(connection.input).data();
            assertEquals(
                0,
                update.getAsJsonArray("definitions")
                    .size());
            assertEquals(
                20,
                update.getAsJsonArray("updates")
                    .get(0)
                    .getAsJsonObject()
                    .get("quantity")
                    .getAsLong());
            assertTrue(
                !update.has("icons") || update.get("icons")
                    .isJsonNull());
            assertTrue(
                !update.has("unkeyed") || update.get("unkeyed")
                    .isJsonNull());
        }
    }

    @Test
    void changesBeforeOpeningStreamCollapseToLatestAbsoluteValues() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        try (Fixture fixture = new Fixture(Collections.singletonList(row(iron, "Iron", 1)))) {
            String token = fixture.reserve();
            fixture.publish(2, Collections.singletonList(row(iron, "Iron", 20)));
            fixture.publish(3, Collections.singletonList(row(iron, "Iron", 35)));
            try (Connection connection = fixture.open(token)) {
                assertEquals("ready", readEvent(connection.input).name());
                Event update = readEvent(connection.input);
                assertEquals(
                    35,
                    update.data()
                        .getAsJsonArray("updates")
                        .get(0)
                        .getAsJsonObject()
                        .get("quantity")
                        .getAsLong());
            }
        }
    }

    @Test
    void updatesPreserveExactKeysCraftabilityAndUnkeyedReplacement() throws Exception {
        StableKey removed = TestGridFixtures.key(50);
        StableKey added = TestGridFixtures.key(51);
        try (Fixture fixture = new Fixture(Arrays.asList(row(removed, "Stone", 4), row(null, "Essentia", 9)));
            Connection connection = fixture.open(fixture.reserve())) {
            assertEquals("ready", readEvent(connection.input).name());
            StorageObservationFixtures.Row craftable = new StorageObservationFixtures.Row(
                row(added, "Iron", 0).resource(),
                true);
            fixture.publish(2, Arrays.asList(craftable, row(null, "Essentia", 3)));
            Event update = readEvent(connection.input);
            assertEquals("delta", update.name());
            assertEquals(
                removed.toString(),
                update.data()
                    .getAsJsonArray("removed")
                    .get(0)
                    .getAsString());
            assertEquals(
                added.toString(),
                update.data()
                    .getAsJsonArray("updates")
                    .get(0)
                    .getAsJsonObject()
                    .get("itemKey")
                    .getAsString());
            assertTrue(
                update.data()
                    .getAsJsonArray("updates")
                    .get(0)
                    .getAsJsonObject()
                    .get("craftable")
                    .getAsBoolean());
            assertEquals(
                3,
                update.data()
                    .getAsJsonArray("unkeyed")
                    .get(0)
                    .getAsJsonObject()
                    .get("quantity")
                    .getAsLong());
            fixture.publish(3, Collections.singletonList(craftable));
            assertEquals(
                0,
                readEvent(connection.input).data()
                    .getAsJsonArray("unkeyed")
                    .size());
        }
    }

    @Test
    void noOpReadDoesNotSendAnUpdate() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        try (Fixture fixture = new Fixture(Collections.singletonList(row(iron, "Iron", 1)));
            Connection connection = fixture.open(fixture.reserve())) {
            assertEquals("ready", readEvent(connection.input).name());
            fixture.publish(2, Collections.singletonList(row(iron, "Iron", 1)));
            fixture.publish(3, Collections.singletonList(row(iron, "Iron", 9)));
            Event update = readEvent(connection.input);
            assertEquals(
                9,
                update.data()
                    .getAsJsonArray("updates")
                    .get(0)
                    .getAsJsonObject()
                    .get("quantity")
                    .getAsLong());
            assertEquals(
                "2",
                update.data()
                    .getAsJsonObject("view")
                    .get("revision")
                    .getAsString());
        }
    }

    @Test
    void subscriptionsAreSingleUseAndBoundToPrincipalAndIconMode() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            String token = fixture.reserve();
            fixture.requestSession.set(new AuthSession(WebPrincipal.localhost(), Long.MAX_VALUE));
            assertEquals(400, fixture.status(token));
            fixture.requestSession.set(fixture.session);
            fixture.requestIcons.set(true);
            assertEquals(400, fixture.status(token));
            fixture.requestIcons.set(false);
            try (Connection connection = fixture.open(token)) {
                assertEquals("ready", readEvent(connection.input).name());
                assertEquals(400, fixture.status(token));
            }
        }
    }

    @Test
    void expiredUnopenedReservationCannotOpenOrConsumeAdmissionForever() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            TerminalRegistry.Bootstrap expired = fixture.state
                .reserve(fixture.grid, fixture.session, false, null, System.currentTimeMillis() - 20_000);
            assertNotNull(expired);
            assertEquals(400, fixture.status(expired.subscription()));
            assertNotNull(fixture.reserve());
        }
    }

    @Test
    void nativeInvalidationClosesStreamAndRejectsUnopenedSnapshot() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList());
            Connection connection = fixture.open(fixture.reserve())) {
            String unopened = fixture.reserve();
            assertEquals("ready", readEvent(connection.input).name());
            fixture.state.invalidate(fixture.grid, 5);
            assertEquals("invalidate", readEvent(connection.input).name());
            assertEquals(400, fixture.status(unopened));
            fixture.publish(2, Collections.singletonList(row(TestGridFixtures.key(5), "New", 10)));
            assertNotNull(fixture.reserve());
        }
    }

    @Test
    void staleGenerationCannotReplaceCurrentDataOrRetireCurrentView() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            fixture.state.activate(fixture.grid, 6, System.currentTimeMillis());
            fixture.state.observed(fixture.grid, 6, observation(1, Collections.emptyList()));
            String token = fixture.reserve();
            fixture.state.retire(fixture.grid, 5);
            fixture.publish(2, Collections.singletonList(row(TestGridFixtures.key(3), "Stale", 100)));
            fixture.state.observed(
                fixture.grid,
                6,
                observation(2, Collections.singletonList(row(TestGridFixtures.key(4), "Current", 8))));
            try (Connection connection = fixture.open(token)) {
                assertEquals("ready", readEvent(connection.input).name());
                Event update = readEvent(connection.input);
                assertEquals(
                    "Current",
                    update.data()
                        .getAsJsonArray("definitions")
                        .get(0)
                        .getAsJsonObject()
                        .get("displayName")
                        .getAsString());
            }
        }
    }

    @Test
    void tokenRevocationClosesStreamWithoutAnotherResourceEvent() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList());
            Connection connection = fixture.open(fixture.reserve())) {
            assertEquals("ready", readEvent(connection.input).name());
            fixture.session.invalidate();
            fixture.publish(2, Collections.singletonList(row(TestGridFixtures.key(4), "Secret", 50)));
            assertEquals("revoked", readEvent(connection.input).name());
            assertNull(connection.input.readLine());
        }
    }

    @Test
    void reservationsBoundPerAccountAdmissionBeforeAnyStreamOpens() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            for (int i = 0; i < 4; i++) assertNotNull(fixture.reserve());
            assertNull(fixture.state.reserve(fixture.grid, fixture.session, false, null, System.currentTimeMillis()));
        }
    }

    @Test
    void openStreamReleasesOrdinaryHttpWorker() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList());
            Connection connection = fixture.open(fixture.reserve())) {
            assertEquals("ready", readEvent(connection.input).name());
            HttpURLConnection ordinary = (HttpURLConnection) new URL(fixture.base() + "/ordinary").openConnection();
            ordinary.setReadTimeout(3000);
            assertEquals(204, ordinary.getResponseCode());
            ordinary.disconnect();
        }
    }

    @Test
    void stalledReadersAreCancelledAndWriterCapacityBecomesUsableAgain() throws Exception {
        List<Socket> stalled = new ArrayList<>();
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            try {
                for (int client = 0; client < 4; client++) {
                    Socket socket = new Socket();
                    socket.setReceiveBufferSize(1024);
                    socket.setSoTimeout(3000);
                    socket.connect(fixture.server.getAddress());
                    stalled.add(socket);
                    socket.getOutputStream()
                        .write(
                            ("GET /events?" + fixture.reserve() + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream()
                        .flush();
                    int matched = 0;
                    byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                    while (matched < terminator.length) {
                        int value = socket.getInputStream()
                            .read();
                        assertTrue(value >= 0);
                        matched = value == terminator[matched] ? matched + 1 : 0;
                    }
                }
                char[] label = new char[900_000];
                Arrays.fill(label, 'x');
                String firstLabel = new String(label);
                label[0] = 'y';
                String secondLabel = new String(label);
                long revision = 2;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
                boolean recovered = false;
                while (System.nanoTime() < deadline && !recovered) {
                    // A single event can fit entirely in an OS send buffer without blocking a writer.
                    // Keep changing definitions below the per-event limit until unread connections exert backpressure.
                    String name = (revision & 1) == 0 ? firstLabel : secondLabel;
                    fixture.publish(revision++, Collections.singletonList(row(TestGridFixtures.key(99), name, 1)));
                    fixture.state.activate(fixture.grid, 5, System.currentTimeMillis());
                    TerminalRegistry.Bootstrap reservation = fixture.state
                        .reserve(fixture.grid, fixture.session, false, null, System.currentTimeMillis());
                    if (reservation == null) {
                        Thread.sleep(100);
                        continue;
                    }
                    HttpURLConnection probe = fixture.connection(reservation.subscription());
                    try {
                        int status = probe.getResponseCode();
                        if (status == 200) {
                            try (BufferedReader input = new BufferedReader(
                                new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) {
                                assertEquals("ready", readEvent(input).name());
                                recovered = true;
                            }
                        } else assertEquals(503, status);
                    } finally {
                        probe.disconnect();
                    }
                    if (!recovered) Thread.sleep(100);
                }
                assertTrue(recovered, "Slow readers must not permanently exhaust the bounded writer pool");
            } finally {
                for (Socket socket : stalled) socket.close();
            }
        }
    }

    @Test
    void nativePermissionRevocationClosesAllViewersWithoutServerTicks() throws Exception {
        TestGridFixtures.TestGrid nativeGrid = TestGridFixtures.grid(77241);
        StableKey key = TestGridFixtures.resolvedKey(nativeGrid);
        try (
            Fixture fixture = new Fixture(
                key,
                TestGridFixtures.principal(TestGridFixtures.OWNER_ID),
                Collections.emptyList());
            Connection first = fixture.open(fixture.reserve());
            Connection second = fixture.open(fixture.reserve())) {
            assertEquals("ready", readEvent(first.input).name());
            assertEquals("ready", readEvent(second.input).name());
            nativeGrid.withoutSources();
            assertEquals("revoked", readEvent(first.input).name());
            assertEquals("revoked", readEvent(second.input).name());
        }
    }

    private static StorageObservationFixtures.Row row(StableKey key, String name, long quantity) {
        return new StorageObservationFixtures.Row(
            new ResourceSnapshot(
                new ResourceDescriptor("test", "resource", 0, 0, name, ResourceType.ITEM, key, null),
                quantity),
            false);
    }

    @Desugar
    private record Event(String name, String id, JsonObject data) {}

    private static @NotNull Event readEvent(BufferedReader input) throws Exception {
        String name = null;
        String id = null;
        JsonObject data = null;
        for (String line; (line = input.readLine()) != null;) {
            if (line.startsWith("event: ")) name = line.substring(7);
            if (line.startsWith("id: ")) id = line.substring(4);
            if (line.startsWith("data: ")) data = new JsonParser().parse(line.substring(6))
                .getAsJsonObject();
            if (line.isEmpty() && name != null) return new Event(name, id, data);
        }
        return fail("Stream ended before its next event");
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

    private static StorageObservation observation(long revision, List<StorageObservationFixtures.Row> rows) {
        return StorageObservationFixtures.observation(revision * 100, true, true, rows, Collections.emptyList());
    }

    private static final class Fixture implements AutoCloseable {

        private final StableKey grid;
        // The fake world owns its grid; the identity registry deliberately retains only a weak reference.
        private final IAEGrid nativeGrid;
        private final WebPrincipal principal;
        private final AuthSession session;
        private final AtomicReference<AuthSession> requestSession = new AtomicReference<>();
        private final AtomicBoolean requestIcons = new AtomicBoolean();
        private final ExecutorService ordinary = Executors.newSingleThreadExecutor();
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final TerminalRegistry state = new TerminalRegistry();
        private final TerminalStreams streams = new TerminalStreams(state, CoreEngine.GRID_IDENTITIES, null);

        private Fixture(List<StorageObservationFixtures.Row> rows) throws Exception {
            this(TestGridFixtures.resolvedKey(TestGridFixtures.grid(32)), WebPrincipal.admin(), rows);
        }

        private Fixture(StableKey grid, WebPrincipal principal, List<StorageObservationFixtures.Row> rows)
            throws Exception {
            this.grid = grid;
            nativeGrid = CoreEngine.GRID_IDENTITIES.getGrid(grid);
            assertNotNull(nativeGrid);
            this.principal = principal;
            session = new AuthSession(principal, Long.MAX_VALUE);
            requestSession.set(session);
            state.activate(grid, 5, System.currentTimeMillis());
            state.observed(grid, 5, observation(1, rows));
            server.setExecutor(ordinary);
            server.createContext(
                "/events",
                exchange -> streams.open(
                    new AE2Controller.RequestContext(exchange, requestSession.get()),
                    this.grid,
                    exchange.getRequestURI()
                        .getRawQuery(),
                    requestIcons.get()));
            server.createContext("/ordinary", exchange -> {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
        }

        private String reserve() {
            TerminalRegistry.Bootstrap snapshot = state.reserve(grid, session, false, null, System.currentTimeMillis());
            assertNotNull(snapshot);
            return snapshot.subscription();
        }

        private String base() {
            return "http://127.0.0.1:" + server.getAddress()
                .getPort();
        }

        private HttpURLConnection connection(String token) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(base() + "/events?" + token).openConnection();
            connection.setReadTimeout(3000);
            return connection;
        }

        private int status(String token) throws Exception {
            HttpURLConnection connection = connection(token);
            try {
                return connection.getResponseCode();
            } finally {
                connection.disconnect();
            }
        }

        private Connection open(String token) throws Exception {
            return new Connection(connection(token));
        }

        private void publish(long revision, List<StorageObservationFixtures.Row> rows) {
            state.observed(grid, 5, observation(revision, rows));
        }

        @Override
        public void close() {
            streams.close();
            server.stop(0);
            ordinary.shutdownNow();
        }
    }
}
