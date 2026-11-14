package pl.kuba6000.ae2webintegration.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kuba6000.ae2webintegration.core.CoreEngine.GRID_IDENTITIES;
import static pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.observation;
import static pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.readAnyEvent;
import static pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.readEvent;
import static pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.row;
import static pl.kuba6000.ae2webintegration.core.TestGridFixtures.OWNER_ID;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.CONTROLLER_ONLINE;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.Uninterruptibles;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.Connection;
import pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.Event;
import pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.Fixture;
import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.live.LiveStreams;
import pl.kuba6000.ae2webintegration.core.terminal.StorageTopic;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentTrackingService;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservationFixtures;

class TerminalStreamsHttpTest extends GridTestScope {

    private TestGridFixtures.TestGrid nativeGrid;

    @Test
    void overlappingResetsCloseEachExchangeOnceAndAllowNewStreams() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicBoolean closeStarted = new AtomicBoolean();
        ExecutorService resets = Executors.newSingleThreadExecutor();
        OutputStream output = new OutputStream() {

            @Override
            public void write(int value) {}

            @Override
            public void write(byte @NotNull [] bytes, int offset, int length) {
                if (new String(bytes, offset, length, UTF_8).startsWith("event: heartbeat")) ready.countDown();
            }
        };
        TestGridFixtures.TestExchange exchange = new TestGridFixtures.TestExchange(null) {

            @Override
            public InputStream getRequestBody() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public OutputStream getResponseBody() {
                return output;
            }

            @Override
            public void close() {
                assertTrue(closeStarted.compareAndSet(false, true), "An HTTP response must finish only once");
                closing.countDown();
                assertTrue(Uninterruptibles.awaitUninterruptibly(releaseClose, 3, SECONDS));
            }
        };
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            fixture.streams.open(
                new AE2Controller.RequestContext(exchange, fixture.session),
                StorageTopic.keyOf(fixture.grid),
                fixture.reserve(),
                false);
            assertTrue(ready.await(2, SECONDS));
            Future<?> reset = resets.submit(fixture.streams::reset);
            try {
                assertTrue(closing.await(2, SECONDS));
                assertDoesNotThrow(fixture.streams::reset);
            } finally {
                releaseClose.countDown();
                reset.get(3, SECONDS);
            }
            fixture.state.activate(fixture.grid, 5, System.currentTimeMillis());
            fixture.publish(2, Collections.emptyList());
            try (Connection reopened = fixture.open(fixture.reserve())) {
                assertEquals("ready", readEvent(reopened.input).name());
            }
        } finally {
            releaseClose.countDown();
            resets.shutdownNow();
        }
    }

    @Test
    void exchangeCloseFailureDoesNotAbortResetOrLaterStreaming() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean closeAttempted = new AtomicBoolean();
        OutputStream output = new OutputStream() {

            @Override
            public void write(int value) {
                throw new AssertionError("Expected a complete encoded event");
            }

            @Override
            public void write(byte @NotNull [] bytes, int offset, int length) {
                if (new String(bytes, offset, length, UTF_8).startsWith("event: heartbeat")) ready.countDown();
            }
        };
        TestGridFixtures.TestExchange failedClose = new TestGridFixtures.TestExchange(null) {

            @Override
            public InputStream getRequestBody() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public OutputStream getResponseBody() {
                return output;
            }

            @Override
            public void close() {
                closeAttempted.set(true);
                throw new IllegalStateException("Exchange close failed");
            }
        };
        StableKey iron = TestGridFixtures.key(51);
        try (Fixture fixture = new Fixture(Collections.singletonList(row(iron, "Iron", 1)))) {
            fixture.streams.open(
                new AE2Controller.RequestContext(failedClose, fixture.session),
                StorageTopic.keyOf(fixture.grid),
                fixture.reserve(),
                false);
            assertTrue(ready.await(2, SECONDS));
            try (Connection healthy = fixture.open(fixture.reserve())) {
                assertEquals("ready", readAnyEvent(healthy.input).name());
                assertEquals("heartbeat", readAnyEvent(healthy.input).name());
                fixture.fillAdmission();
                assertDoesNotThrow(fixture.streams::reset);
                assertTrue(closeAttempted.get());
                assertClosedWithoutDelta(healthy.input);
                fixture.state.activate(fixture.grid, 5, System.currentTimeMillis());
                fixture.publish(1, Collections.singletonList(row(iron, "Iron", 1)));
                try (Connection replacement = fixture.open(fixture.reserve())) {
                    assertEquals("ready", readEvent(replacement.input).name());
                    fixture.publish(2, Collections.singletonList(row(iron, "Iron", 2)));
                    assertEquals("delta", readEvent(replacement.input).name());
                }
            }
        }
    }

    @Test
    void permissionFailureClosesOnlyItsSessionAndReclaimsAdmission() throws Exception {
        AtomicBoolean failFirst = new AtomicBoolean();
        CountDownLatch failedCheck = new CountDownLatch(1);
        UUID firstUser = TestGridFixtures.playerIdentity(OWNER_ID).uuid;
        nativeGrid = new TestGridFixtures.TestGrid(77242, false, CONTROLLER_ONLINE, 8) {

            @Override
            public boolean web$hasAccess(@NotNull UUID playerId) {
                if (failFirst.get() && firstUser.equals(playerId)) {
                    failedCheck.countDown();
                    throw new IllegalStateException("Permission provider failed");
                }
                return super.web$hasAccess(playerId);
            }
        };
        StableKey key = TestGridFixtures.resolvedKey(nativeGrid);
        StableKey iron = TestGridFixtures.key(51);
        try (
            Fixture fixture = new Fixture(
                key,
                TestGridFixtures.principal(OWNER_ID),
                Collections.singletonList(row(iron, "Iron", 1)));
            Connection first = fixture.open(fixture.reserve())) {
            assertEquals("ready", readAnyEvent(first.input).name());
            assertEquals("heartbeat", readAnyEvent(first.input).name());
            AuthSession secondSession = new AuthSession(TestGridFixtures.principal(8), Long.MAX_VALUE);
            fixture.requestSession.set(secondSession);
            try (Connection second = fixture.open(fixture.reserve(secondSession))) {
                assertEquals("ready", readAnyEvent(second.input).name());
                assertEquals("heartbeat", readAnyEvent(second.input).name());
                fixture.fillAdmission();
                failFirst.set(true);
                assertTrue(failedCheck.await(2, SECONDS));
                assertClosedWithoutDelta(first.input);
                assertNotNull(fixture.awaitAdmission());
                for (int quantity = 2; quantity <= 3; quantity++) {
                    fixture.publish(quantity, Collections.singletonList(row(iron, "Iron", quantity)));
                    Event event = readEvent(second.input);
                    assertEquals("delta", event.name());
                    assertEquals(
                        quantity,
                        event.data()
                            .getAsJsonArray("updates")
                            .get(0)
                            .getAsJsonObject()
                            .get("quantity")
                            .getAsLong());
                }
            }
        }
    }

    @Test
    void reservedTerminalReceivesAsynchronousRecentDataAfterAnEmptyBootstrap() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        List<StorageObservationFixtures.Row> rows = Collections.singletonList(row(iron, "Iron", 1));
        try (Fixture fixture = new Fixture(rows);
            RecentTrackingService recent = new RecentTrackingService(fixture.state, 100)) {
            recent.open(fixture.grid, 5, false, true, 100, 1000);
            TerminalRegistry.Bootstrap bootstrap = fixture.state
                .reserve(fixture.grid, fixture.session, false, null, null, System.currentTimeMillis());
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
    void changedOperationCountsPublishEvenWhenRecentQuantitiesAndTimesStayEqual() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        try (Fixture fixture = new Fixture(Collections.singletonList(row(iron, "Iron", 1)))) {
            RecentResourceActivity.Summary initial = new RecentResourceActivity.Summary(
                120000,
                3600000,
                5000,
                6000,
                6000L,
                null,
                1000L,
                null,
                true,
                false,
                Collections.singletonMap(iron, new RecentResourceActivity.Amount("10", "5", "1", "1", null)));
            fixture.state.recent(fixture.grid, 5, fixture.state.recentToken(fixture.grid, 5), initial);
            try (Connection connection = fixture.open(fixture.reserve())) {
                assertEquals("ready", readEvent(connection.input).name());
                RecentResourceActivity.Summary changed = initial.withResources(
                    Collections.singletonMap(iron, new RecentResourceActivity.Amount("10", "5", "2", "3", null)));
                fixture.state.recent(fixture.grid, 5, fixture.state.recentToken(fixture.grid, 5), changed);
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
                assertEquals(
                    "10",
                    amount.get("input")
                        .getAsString());
                assertEquals(
                    "5",
                    amount.get("output")
                        .getAsString());
                assertEquals(
                    "2",
                    amount.get("inputOperations")
                        .getAsString());
                assertEquals(
                    "3",
                    amount.get("outputOperations")
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
                .reserve(fixture.grid, fixture.session, false, null, null, System.currentTimeMillis());
            assertNotNull(bootstrap);
            RecentResourceActivity.Summary initialRecent = bootstrap.recent();
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
                        Collections.singletonList(
                            new StorageObservation.Flow(iron, Long.MAX_VALUE, 100, Long.MAX_VALUE - 1, 2)),
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
                assertEquals(
                    "9223372036854775806",
                    amount.get("inputOperations")
                        .getAsString());
                assertEquals(
                    "2",
                    amount.get("outputOperations")
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
                    "0",
                    delta.getAsJsonObject("recent")
                        .getAsJsonObject("resources")
                        .getAsJsonObject(iron.toString())
                        .get("inputOperations")
                        .getAsString());
                assertEquals(
                    "0",
                    delta.getAsJsonObject("recent")
                        .getAsJsonObject("resources")
                        .getAsJsonObject(iron.toString())
                        .get("outputOperations")
                        .getAsString());
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
                .reserve(fixture.grid, shortLogin, false, null, null, System.currentTimeMillis());
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
            } catch (IOException closedConnection) {
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
                .reserve(fixture.grid, other, false, null, null, System.currentTimeMillis());
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
        LiveHub hub = new LiveHub();
        TerminalRegistry state = new TerminalRegistry(hub);
        state.activate(grid, 5, System.currentTimeMillis());
        state.observed(grid, 5, observation(1, Collections.singletonList(row(iron, "Iron", 1))));
        TerminalRegistry.Bootstrap bootstrap = state
            .reserve(grid, session, false, null, null, System.currentTimeMillis());
        assertNotNull(bootstrap);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch sent = new CountDownLatch(2);
        List<String> deltas = Collections.synchronizedList(new ArrayList<>());
        OutputStream output = new OutputStream() {

            @Override
            public void write(int value) {
                throw new AssertionError("Expected a complete encoded event");
            }

            @Override
            public void write(byte @NotNull [] bytes, int offset, int length) throws IOException {
                String frame = new String(bytes, offset, length, UTF_8);
                if (!frame.startsWith("event: delta")) return;
                if (deltas.isEmpty()) {
                    writing.countDown();
                    try {
                        if (!release.await(3, SECONDS)) throw new IOException("Writer not released");
                    } catch (InterruptedException interrupted) {
                        throw new IOException(interrupted);
                    }
                }
                deltas.add(frame);
                sent.countDown();
            }
        };
        TestGridFixtures.TestExchange exchange = new TestGridFixtures.TestExchange(null) {

            @Override
            public InputStream getRequestBody() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public OutputStream getResponseBody() {
                return output;
            }
        };
        try (LiveStreams streams = new LiveStreams(hub, GRID_IDENTITIES, null)) {
            streams.open(
                new AE2Controller.RequestContext(exchange, session),
                StorageTopic.keyOf(grid),
                bootstrap.subscription(),
                false);
            state.observed(grid, 5, observation(2, Collections.singletonList(row(iron, "Iron", 10))));
            assertTrue(writing.await(2, SECONDS));
            state.observed(grid, 5, observation(3, Collections.singletonList(row(iron, "Iron", 20))));
            state.observed(grid, 5, observation(4, Collections.emptyList()));
            release.countDown();
            assertTrue(sent.await(2, SECONDS));
            Event first = readEvent(new BufferedReader(new StringReader(deltas.get(0))));
            Event second = readEvent(new BufferedReader(new StringReader(deltas.get(1))));
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
                .reserve(fixture.grid, fixture.session, false, null, null, System.currentTimeMillis() - 20_000);
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
    void renewalsExtendOnlyTheirOwnedLiveStreamAndCannotReviveExpiredTokens() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            String firstToken = fixture.reserve();
            String secondToken = fixture.reserve();
            try (Connection first = fixture.open(firstToken); Connection second = fixture.open(secondToken)) {
                assertEquals("ready", readEvent(first.input).name());
                assertEquals("ready", readEvent(second.input).name());
                long now = System.currentTimeMillis();
                AuthSession otherLogin = new AuthSession(fixture.principal, Long.MAX_VALUE);
                assertFalse(fixture.state.renew(fixture.grid, otherLogin, secondToken, now + 60_000));
                assertFalse(fixture.state.renew(TestGridFixtures.key(999), fixture.session, secondToken, now + 60_000));
                assertTrue(fixture.state.renew(fixture.grid, fixture.session, firstToken, now + 60_000));
                fixture.state.viewedGrids(now + 100_000);
                assertEquals("reset", readEvent(second.input).name());
                fixture.publish(2, Collections.singletonList(row(TestGridFixtures.key(98), "Iron", 2)));
                assertEquals("delta", readEvent(first.input).name());
                assertFalse(fixture.state.renew(fixture.grid, fixture.session, secondToken, now + 100_000));
                assertFalse(fixture.state.renew(fixture.grid, fixture.session, firstToken, now + 160_000));
                assertEquals("reset", readEvent(first.input).name());
            }
        }
    }

    @Test
    void replacementCanMoveToAnotherGridButCannotRetireAnotherLoginAtCapacity() throws Exception {
        boolean publicMode = Config.INSTANCE.general.publicMode;
        Config.INSTANCE.general.publicMode = true;
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            String token = fixture.reserve();
            try (Connection connection = fixture.open(token)) {
                assertEquals("ready", readEvent(connection.input).name());
                for (int i = 0; i < 5; i++) fixture.reserve();
                long now = System.currentTimeMillis();
                AuthSession otherLogin = new AuthSession(fixture.principal, Long.MAX_VALUE);
                assertNull(fixture.state.reserve(fixture.grid, otherLogin, false, null, token, now));
                StableKey otherGrid = TestGridFixtures.key(999);
                assertNull(fixture.state.reserve(otherGrid, fixture.session, false, null, token, now));
                fixture.state.activate(otherGrid, 5, now);
                fixture.state.observed(otherGrid, 5, observation(1, Collections.emptyList()));
                assertNull(fixture.state.reserve(fixture.grid, fixture.session, false, null, "unknown", now));
                fixture.publish(2, Collections.singletonList(row(TestGridFixtures.key(98), "Iron", 2)));
                assertEquals("delta", readEvent(connection.input).name());
                assertNotNull(fixture.state.reserve(otherGrid, fixture.session, false, null, token, now));
                assertEquals("reset", readEvent(connection.input).name());
                assertNull(fixture.state.reserve(fixture.grid, fixture.session, false, null, token, now));
            }
        } finally {
            Config.INSTANCE.general.publicMode = publicMode;
        }
    }

    @Test
    void privateLoginLimitsDoNotBypassGlobalStreamCapacity() throws Exception {
        boolean publicMode = Config.INSTANCE.general.publicMode;
        Config.INSTANCE.general.publicMode = false;
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            for (int i = 0; i < 64; i++) fixture.reserve(new AuthSession(fixture.principal, Long.MAX_VALUE));
            assertNull(
                fixture.state.reserve(fixture.grid, fixture.session, false, null, null, System.currentTimeMillis()));
        } finally {
            Config.INSTANCE.general.publicMode = publicMode;
        }
    }

    @Test
    void abandonedAttachedStreamsExpireWithoutDependingOnSocketFailure() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList());
            Connection connection = fixture.open(fixture.reserve())) {
            assertEquals("ready", readEvent(connection.input).name());
            assertTrue(
                fixture.state.viewedGrids(System.currentTimeMillis() + MINUTES.toMillis(2))
                    .isEmpty());
            assertEquals("reset", readEvent(connection.input).name());
            fixture.state.activate(fixture.grid, 5, System.currentTimeMillis());
            fixture.publish(2, Collections.emptyList());
            assertNotNull(fixture.reserve());
        }
    }

    @Test
    void privateReservationsBoundEachLoginIndependentlyBeforeAnyStreamOpens() throws Exception {
        boolean publicMode = Config.INSTANCE.general.publicMode;
        Config.INSTANCE.general.publicMode = false;
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            for (int i = 0; i < 6; i++) assertNotNull(fixture.reserve());
            assertNull(
                fixture.state.reserve(fixture.grid, fixture.session, false, null, null, System.currentTimeMillis()));
            AuthSession otherLogin = new AuthSession(fixture.principal, Long.MAX_VALUE);
            for (int i = 0; i < 6; i++) assertNotNull(fixture.reserve(otherLogin));
            assertNull(fixture.state.reserve(fixture.grid, otherLogin, false, null, null, System.currentTimeMillis()));
        } finally {
            Config.INSTANCE.general.publicMode = publicMode;
        }
    }

    @Test
    void publicReservationsShareAccountLimitAcrossLoginsButNotAcrossAccounts() throws Exception {
        boolean publicMode = Config.INSTANCE.general.publicMode;
        Config.INSTANCE.general.publicMode = true;
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            AuthSession otherLogin = new AuthSession(fixture.principal, Long.MAX_VALUE);
            for (int i = 0; i < 6; i++) assertNotNull(fixture.reserve(i % 2 == 0 ? fixture.session : otherLogin));
            assertNull(fixture.state.reserve(fixture.grid, otherLogin, false, null, null, System.currentTimeMillis()));
            assertNull(
                fixture.state.reserve(fixture.grid, fixture.session, false, null, null, System.currentTimeMillis()));
            assertNotNull(fixture.reserve(new AuthSession(WebPrincipal.localhost(), Long.MAX_VALUE)));
        } finally {
            Config.INSTANCE.general.publicMode = publicMode;
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
    void nativePermissionRevocationClosesAllViewersWithoutServerTicks() throws Exception {
        TestGridFixtures.TestGrid nativeGrid = TestGridFixtures.grid(77241);
        StableKey key = TestGridFixtures.resolvedKey(nativeGrid);
        try (Fixture fixture = new Fixture(key, TestGridFixtures.principal(OWNER_ID), Collections.emptyList());
            Connection first = fixture.open(fixture.reserve());
            Connection second = fixture.open(fixture.reserve())) {
            assertEquals("ready", readEvent(first.input).name());
            assertEquals("ready", readEvent(second.input).name());
            nativeGrid.withoutSources();
            assertEquals("revoked", readEvent(first.input).name());
            assertEquals("revoked", readEvent(second.input).name());
        }
    }

    private static void assertClosedWithoutDelta(BufferedReader input) throws IOException {
        // A writer can finish an invalidate control frame while reset closes the connection.
        try {
            for (String line; (line = input.readLine()) != null;) {
                assertFalse(line.equals("event: delta"), "A failed or reset session must not send another delta");
            }
        } catch (IOException closed) {
            // Closing between chunks ends the chunked body without its terminator, which is still a closed stream.
            if (!"Premature EOF".equals(closed.getMessage())) throw closed;
        }
    }
}
