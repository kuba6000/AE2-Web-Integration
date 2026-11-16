package pl.kuba6000.ae2webintegration.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static pl.kuba6000.ae2webintegration.core.CoreEngine.GRID_IDENTITIES;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.live.LiveStreams;
import pl.kuba6000.ae2webintegration.core.terminal.StorageTopic;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservationFixtures;

/** Shared HTTP fixtures for the live terminal stream tests. */
final class TerminalStreamFixtures {

    private TerminalStreamFixtures() {}

    static StorageObservationFixtures.Row row(StableKey key, String name, long quantity) {
        return new StorageObservationFixtures.Row(
            new ResourceSnapshot(
                new ResourceDescriptor("test", "resource", 0, 0, name, ResourceType.ITEM, key, null),
                quantity),
            false);
    }

    @Desugar
    record Event(String name, String id, JsonObject data) {}

    static @NotNull Event readEvent(BufferedReader input) throws Exception {
        Event event;
        do {
            event = readAnyEvent(input);
        } while (event.name()
            .equals("heartbeat"));
        return event;
    }

    static @NotNull Event readAnyEvent(BufferedReader input) throws Exception {
        String name = null;
        String id = null;
        JsonObject data = null;
        for (String line; (line = input.readLine()) != null;) {
            if (line.startsWith("event: ")) name = line.substring(7);
            if (line.startsWith("id: ")) id = line.substring(4);
            if (line.startsWith("data: ")) data = new JsonParser().parse(line.substring(6))
                .getAsJsonObject();
            if (line.isEmpty() && name != null) {
                return new Event(name, id, data);
            }
        }
        return fail("Stream ended before its next event");
    }

    static final class Connection implements AutoCloseable {

        final HttpURLConnection connection;
        final BufferedReader input;

        Connection(HttpURLConnection connection) throws Exception {
            this.connection = connection;
            input = new BufferedReader(new InputStreamReader(connection.getInputStream(), UTF_8));
        }

        @Override
        public void close() throws Exception {
            input.close();
            connection.disconnect();
        }
    }

    static StorageObservation observation(long revision, List<StorageObservationFixtures.Row> rows) {
        return StorageObservationFixtures.observation(revision * 100, true, true, rows, Collections.emptyList());
    }

    static final class Fixture implements AutoCloseable {

        final StableKey grid;
        // The fake world owns its grid; the identity registry deliberately retains only a weak reference.
        final IAEGrid nativeGrid;
        final WebPrincipal principal;
        final AuthSession session;
        final AtomicReference<AuthSession> requestSession = new AtomicReference<>();
        final AtomicBoolean requestIcons = new AtomicBoolean();
        final ExecutorService ordinary = Executors.newSingleThreadExecutor();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final LiveHub hub = new LiveHub();
        final TerminalRegistry state = new TerminalRegistry(hub);
        final LiveStreams streams = new LiveStreams(hub, GRID_IDENTITIES, null);

        Fixture(List<StorageObservationFixtures.Row> rows) throws Exception {
            this(TestGridFixtures.grid(32), WebPrincipal.admin(), rows);
        }

        Fixture(StableKey grid, WebPrincipal principal, List<StorageObservationFixtures.Row> rows) throws Exception {
            this(GRID_IDENTITIES.getGrid(grid), principal, rows);
        }

        Fixture(IAEGrid nativeGrid, WebPrincipal principal, List<StorageObservationFixtures.Row> rows)
            throws Exception {
            assertNotNull(nativeGrid);
            this.nativeGrid = nativeGrid;
            grid = TestGridFixtures.resolvedKey(this.nativeGrid);
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
                    StorageTopic.keyOf(this.grid),
                    exchange.getRequestURI()
                        .getRawQuery(),
                    requestIcons.get()));
            server.createContext("/ordinary", exchange -> {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
        }

        String reserve() {
            return reserve(session);
        }

        String reserve(AuthSession login) {
            TerminalRegistry.Bootstrap snapshot = state
                .reserve(grid, login, false, null, null, System.currentTimeMillis());
            assertNotNull(snapshot);
            return snapshot.subscription();
        }

        void fillAdmission() {
            for (int attempt = 0; attempt < 64; attempt++) {
                if (state.reserve(grid, session, false, null, null, System.currentTimeMillis()) == null) return;
            }
            fail("Expected bounded live admission");
        }

        TerminalRegistry.Bootstrap awaitAdmission() throws InterruptedException {
            long deadline = System.nanoTime() + SECONDS.toNanos(2);
            TerminalRegistry.Bootstrap available;
            do {
                available = state.reserve(grid, session, false, null, null, System.currentTimeMillis());
                if (available != null) return available;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            return fail("Closed stream must release live admission before its lease expires");
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress()
                .getPort();
        }

        HttpURLConnection connection(String token) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(base() + "/events?" + token).openConnection();
            connection.setReadTimeout(3000);
            return connection;
        }

        int status(String token) throws Exception {
            HttpURLConnection connection = connection(token);
            try {
                return connection.getResponseCode();
            } finally {
                connection.disconnect();
            }
        }

        Connection open(String token) throws Exception {
            return new Connection(connection(token));
        }

        void publish(long revision, List<StorageObservationFixtures.Row> rows) {
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
