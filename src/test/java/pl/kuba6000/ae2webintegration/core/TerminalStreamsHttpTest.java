package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.http.stream.TerminalStreams;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;

class TerminalStreamsHttpTest extends GridTestScope {

    @Test
    void openStreamReleasesOrdinaryHttpWorkerAndReceivesLaterPublication() throws Exception {
        StableKey grid = TestGridFixtures.key(32);
        ExecutorService ordinary = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (TerminalStreams streams = new TerminalStreams(() -> null, context -> true)) {
            StorageSnapshot first = new StorageSnapshot(5, 1, 100, true, true, true, Collections.emptyList());
            assertNotNull(streams.awaitSnapshot(grid, 5, () -> first));
            server.setExecutor(ordinary);
            server.createContext(
                "/events",
                exchange -> streams
                    .open(new AE2Controller.RequestContext(exchange, WebPrincipal.admin()), grid, "5:1", false));
            server.createContext("/ordinary", exchange -> {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress()
                .getPort();
            HttpURLConnection connection = (HttpURLConnection) new URL(base + "/events").openConnection();
            connection.setReadTimeout(3000);
            try (BufferedReader input = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("text/event-stream; charset=UTF-8", connection.getContentType());
                assertEquals("ready", event(input));
                HttpURLConnection normal = (HttpURLConnection) new URL(base + "/ordinary").openConnection();
                normal.setReadTimeout(3000);
                assertEquals(204, normal.getResponseCode());
                streams.published(grid, new StorageSnapshot(5, 2, 200, true, true, true, Collections.emptyList()));
                assertEquals("delta", event(input));
            } finally {
                connection.disconnect();
            }
        } finally {
            server.stop(0);
            ordinary.shutdownNow();
        }
    }

    @Test
    void replayPreservesExactKeysAndReplacesUnkeyedRowsIncludingTheirRemoval() throws Exception {
        StableKey removed = TestGridFixtures.key(50);
        StableKey added = TestGridFixtures.key(51);
        try (Fixture fixture = new Fixture(Arrays.asList(row(removed, "Stone", 4), row(null, "Essentia", 9)));
            Connection connection = fixture.open("5:1")) {
            assertEquals("ready", readEvent(connection.input).name());
            fixture.publish(2, Arrays.asList(row(added, "Iron", 20), row(null, "Essentia", 3)));
            Event update = readEvent(connection.input);
            assertEquals("delta", update.name());
            assertEquals("5:2", update.id());
            assertEquals(
                removed.toString(),
                update.data()
                    .getAsJsonArray("removed")
                    .get(0)
                    .getAsString());
            assertEquals(
                added.toString(),
                update.data()
                    .getAsJsonArray("upserts")
                    .get(0)
                    .getAsJsonObject()
                    .get("itemKey")
                    .getAsString());
            assertEquals(
                3,
                update.data()
                    .getAsJsonArray("unkeyed")
                    .get(0)
                    .getAsJsonObject()
                    .get("quantity")
                    .getAsLong());
            fixture.publish(3, Collections.singletonList(row(added, "Iron", 20)));
            assertEquals(
                0,
                readEvent(connection.input).data()
                    .getAsJsonArray("unkeyed")
                    .size());
            try (Connection resumed = fixture.open("5:2")) {
                assertEquals("ready", readEvent(resumed.input).name());
                Event replay = readEvent(resumed.input);
                assertEquals("5:3", replay.id());
                assertEquals(
                    0,
                    replay.data()
                        .getAsJsonArray("unkeyed")
                        .size());
            }
        }
    }

    @Test
    void expiredAndFutureCursorsResetWithoutReturningUnrelatedRows() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            for (int revision = 2; revision <= 70; revision++) fixture.publish(revision, Collections.emptyList());
            try (Connection old = fixture.open("5:1")) {
                assertEquals("ready", readEvent(old.input).name());
                assertEquals("reset", readEvent(old.input).name());
                assertNull(old.input.readLine());
            }
            try (Connection future = fixture.open("5:71")) {
                assertEquals("ready", readEvent(future.input).name());
                assertEquals("reset", readEvent(future.input).name());
            }
        }
    }

    @Test
    void tokenRevocationClosesStreamWithoutAnotherResourceEvent() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList()); Connection connection = fixture.open("5:1")) {
            assertEquals("ready", readEvent(connection.input).name());
            fixture.authenticated.set(false);
            fixture.publish(2, Collections.singletonList(row(TestGridFixtures.key(4), "Secret", 50)));
            assertEquals("revoked", readEvent(connection.input).name());
            assertNull(connection.input.readLine());
        }
    }

    @Test
    void retiringNativeGenerationInvalidatesExistingStreams() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList()); Connection connection = fixture.open("5:1")) {
            assertEquals("ready", readEvent(connection.input).name());
            fixture.streams.retired(fixture.grid, 5);
            assertEquals("invalidate", readEvent(connection.input).name());
        }
    }

    @Test
    void perAccountAdmissionIsBoundedWhileIdleStreamsShareTransportWorkers() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList());
            Connection one = fixture.open("5:1");
            Connection two = fixture.open("5:1");
            Connection three = fixture.open("5:1");
            Connection four = fixture.open("5:1")) {
            assertEquals("ready", readEvent(one.input).name());
            assertEquals("ready", readEvent(two.input).name());
            assertEquals("ready", readEvent(three.input).name());
            assertEquals("ready", readEvent(four.input).name());
            HttpURLConnection fifth = fixture.connection("5:1");
            try {
                assertEquals(503, fifth.getResponseCode());
            } finally {
                fifth.disconnect();
            }
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
                        .write("GET /events?5:1 HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8));
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
                fixture.publish(2, Collections.singletonList(row(TestGridFixtures.key(99), new String(label), 1)));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
                boolean recovered = false;
                while (System.nanoTime() < deadline && !recovered) {
                    HttpURLConnection probe = fixture.connection("5:2");
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
    void nativePermissionRevocationClosesAllViewersAndLeaseRenewalIsShared() throws Exception {
        TestGridFixtures.TestGrid nativeGrid = TestGridFixtures.grid(77241);
        StableKey key = TestGridFixtures.resolvedKey(nativeGrid);
        AtomicInteger renewals = new AtomicInteger();
        try (
            Fixture fixture = new Fixture(
                key,
                TestGridFixtures.principal(TestGridFixtures.OWNER_ID),
                Collections.emptyList());
            Connection first = fixture.open("5:1");
            Connection second = fixture.open("5:1")) {
            assertEquals("ready", readEvent(first.input).name());
            assertEquals("ready", readEvent(second.input).name());
            long now = System.currentTimeMillis();
            fixture.streams
                .onServerTick(TestGridFixtures.ae(nativeGrid), now, (grid, icons) -> renewals.incrementAndGet());
            assertEquals(1, renewals.get());
            nativeGrid.withoutSources();
            fixture.streams
                .onServerTick(TestGridFixtures.ae(nativeGrid), now + 1001, (grid, icons) -> renewals.incrementAndGet());
            assertEquals(1, renewals.get());
            assertEquals("revoked", readEvent(first.input).name());
            assertEquals("revoked", readEvent(second.input).name());
        }
    }

    @Test
    void latePublicationCannotMoveAReplacementViewBackToAnOldGeneration() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            StorageSnapshot replacement = new StorageSnapshot(6, 1, 200, true, true, true, Collections.emptyList());
            assertNotNull(fixture.streams.awaitSnapshot(fixture.grid, 6, () -> replacement));
            try (Connection connection = fixture.open("6:1")) {
                assertEquals("ready", readEvent(connection.input).name());
                fixture.publish(2, Collections.emptyList());
                fixture.streams
                    .published(fixture.grid, new StorageSnapshot(6, 2, 300, true, true, true, Collections.emptyList()));
                Event event = readEvent(connection.input);
                assertEquals("delta", event.name());
                assertEquals("6:2", event.id());
            }
        }
    }

    @Test
    void oldRetirementDoesNotCancelAReplacementAwaitingItsFirstPublication() throws Exception {
        StableKey grid = TestGridFixtures.key(67);
        ExecutorService waiting = Executors.newSingleThreadExecutor();
        try (TerminalStreams streams = new TerminalStreams(() -> null, context -> true)) {
            CountDownLatch registered = new CountDownLatch(1);
            AtomicReference<StorageSnapshot> current = new AtomicReference<>(
                new StorageSnapshot(6, 0, 100, false, false, true, Collections.emptyList()));
            Future<StorageSnapshot> snapshot = waiting.submit(() -> streams.awaitSnapshot(grid, 6, () -> {
                registered.countDown();
                return current.get();
            }));
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            streams.retired(grid, 5);
            StorageSnapshot first = new StorageSnapshot(6, 1, 200, true, true, true, Collections.emptyList());
            current.set(first);
            streams.published(grid, first);
            assertNotNull(snapshot.get(3, TimeUnit.SECONDS));
        } finally {
            waiting.shutdownNow();
        }
    }

    @Test
    void coverageLossRejectsLateHealthyCallbacksFromThePreviousEpoch() throws Exception {
        ExecutorService waiting = Executors.newSingleThreadExecutor();
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            fixture.streams.retired(fixture.grid, 5, 1);
            AtomicReference<StorageSnapshot> current = new AtomicReference<>(
                new StorageSnapshot(5, 1, 200, false, false, true, Collections.emptyList(), 1));
            CountDownLatch registered = new CountDownLatch(1);
            Future<StorageSnapshot> next = waiting.submit(() -> fixture.streams.awaitSnapshot(fixture.grid, 5, () -> {
                registered.countDown();
                return current.get();
            }));
            assertTrue(registered.await(1, TimeUnit.SECONDS));
            fixture.streams
                .published(fixture.grid, new StorageSnapshot(5, 2, 150, true, true, true, Collections.emptyList(), 0));
            assertThrows(TimeoutException.class, () -> next.get(150, TimeUnit.MILLISECONDS));
            StorageSnapshot recovered = new StorageSnapshot(5, 3, 300, true, true, true, Collections.emptyList(), 1);
            current.set(recovered);
            fixture.streams.published(fixture.grid, recovered);
            assertEquals(
                3,
                next.get(1, TimeUnit.SECONDS)
                    .revision());
        } finally {
            waiting.shutdownNow();
        }
    }

    @Test
    void currentSnapshotEpochFencesCallbacksWhenNoRetirementTombstoneExists() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            StorageSnapshot fresh = new StorageSnapshot(5, 3, 300, true, true, true, Collections.emptyList(), 2);
            assertNotNull(fixture.streams.awaitSnapshot(fixture.grid, 5, () -> fresh));
            try (Connection connection = fixture.open("5:3")) {
                assertEquals("ready", readEvent(connection.input).name());
                fixture.streams.published(
                    fixture.grid,
                    new StorageSnapshot(5, 4, 200, true, true, true, Collections.emptyList(), 1));
                fixture.streams.published(
                    fixture.grid,
                    new StorageSnapshot(5, 5, 400, true, true, true, Collections.emptyList(), 2));
                Event next = readEvent(connection.input);
                assertEquals("delta", next.name());
                assertEquals("5:5", next.id());
            }
        }
    }

    private static StorageObservation.Row row(StableKey key, String name, long quantity) {
        return new StorageObservation.Row(
            new ResourceSnapshot("test", "resource", 0, 0, name, ResourceType.ITEM, quantity, key, null),
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

    private static final class Fixture implements AutoCloseable {

        private final StableKey grid;
        private final AtomicBoolean authenticated = new AtomicBoolean(true);
        private final ExecutorService ordinary = Executors.newSingleThreadExecutor();
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final TerminalStreams streams = new TerminalStreams(() -> null, context -> authenticated.get());

        private Fixture(List<StorageObservation.Row> rows) throws Exception {
            this(TestGridFixtures.key(32), WebPrincipal.admin(), rows);
        }

        private Fixture(StableKey grid, WebPrincipal principal, List<StorageObservation.Row> rows) throws Exception {
            this.grid = grid;
            StorageSnapshot snapshot = new StorageSnapshot(5, 1, 100, true, true, true, rows);
            assertNotNull(streams.awaitSnapshot(grid, 5, () -> snapshot));
            server.setExecutor(ordinary);
            server.createContext(
                "/events",
                exchange -> streams.open(
                    new AE2Controller.RequestContext(exchange, principal),
                    grid,
                    exchange.getRequestURI()
                        .getRawQuery(),
                    false));
            server.start();
        }

        private HttpURLConnection connection(String cursor) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getAddress()
                    .getPort() + "/events?" + cursor)
                .openConnection();
            connection.setReadTimeout(3000);
            return connection;
        }

        private Connection open(String cursor) throws Exception {
            return new Connection(connection(cursor));
        }

        private void publish(long revision, List<StorageObservation.Row> rows) {
            streams.published(grid, new StorageSnapshot(5, revision, revision * 100, true, true, true, rows));
        }

        @Override
        public void close() {
            streams.close();
            server.stop(0);
            ordinary.shutdownNow();
        }
    }

    private static @NotNull String event(BufferedReader input) throws Exception {
        String event = null;
        for (String line; (line = input.readLine()) != null;) {
            if (line.startsWith("event: ")) event = line.substring(7);
            if (line.isEmpty() && event != null) return event;
        }
        return fail("Stream ended before its next event");
    }
}
