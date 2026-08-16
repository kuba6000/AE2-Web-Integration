package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.config.CoreDataTestFixture;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

class ServerLifecycleHttpTest {

    private static final class Response {

        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class BlockingPlayerLookup implements IServerPlatform {

        private final UUID playerUuid;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingPlayerLookup(UUID playerUuid) {
            this.playerUuid = playerUuid;
        }

        @Override
        public UUID getOnlinePlayerUUID(String username) {
            return awaitLookup();
        }

        @Override
        public File getConfigDirectory() {
            return null;
        }

        private UUID awaitLookup() {
            entered.countDown();
            try {
                release.await();
                return playerUuid;
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                return null;
            }
        }
    }

    private static final class PostExchange extends TestGridFixtures.TestExchange {

        private final byte[] requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private volatile int responseCode = -1;

        private PostExchange(String requestBody) {
            super(null);
            this.requestBody = requestBody.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String getRequestMethod() {
            return "POST";
        }

        @Override
        public URI getRequestURI() {
            return URI.create("/");
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(requestBody);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int responseCode, long responseLength) {
            this.responseCode = responseCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("192.0.2.10", 12345);
        }
    }

    @TempDir
    File tempDirectory;

    private IConfigValue<Integer> previousPort;
    private IConfigValue<String> previousPassword;
    private IConfigValue<Boolean> previousLocalAccess;
    private IConfigValue<Boolean> previousPublicMode;
    private IConfigValue<Boolean> previousCheckForUpdates;
    private File previousConfigDirectory;
    private IServerPlatform previousServerPlatform;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        CoreEngine.onServerStopped();
        previousPort = ConfigBootstrap.aePortValue;
        previousPassword = ConfigBootstrap.aePasswordValue;
        previousLocalAccess = ConfigBootstrap.allowNoPasswordOnLocalhostValue;
        previousPublicMode = ConfigBootstrap.aePublicModeValue;
        previousCheckForUpdates = ConfigBootstrap.checkForUpdatesValue;
        previousConfigDirectory = Config.getConfigDirectory();
        previousServerPlatform = AE2Controller.serverPlatform;

        port = unusedLoopbackPort();
        ConfigBootstrap.aePortValue = () -> port;
        ConfigBootstrap.aePasswordValue = () -> "lifecycle-password";
        ConfigBootstrap.allowNoPasswordOnLocalhostValue = () -> false;
        ConfigBootstrap.aePublicModeValue = () -> false;
        ConfigBootstrap.checkForUpdatesValue = () -> false;
        Config.init(tempDirectory);
    }

    @AfterEach
    void tearDown() {
        CoreEngine.onServerStopped();
        ConfigBootstrap.aePortValue = previousPort;
        ConfigBootstrap.aePasswordValue = previousPassword;
        ConfigBootstrap.allowNoPasswordOnLocalhostValue = previousLocalAccess;
        ConfigBootstrap.aePublicModeValue = previousPublicMode;
        ConfigBootstrap.checkForUpdatesValue = previousCheckForUpdates;
        AE2Controller.serverPlatform = previousServerPlatform;
        if (previousConfigDirectory != null) {
            Config.init(previousConfigDirectory.getParentFile());
        }
    }

    @Test
    void secondServerLifecycleRebindsRejectsTheOldTokenAndServesANewSession() throws Exception {
        IAE processInterface = TestGridFixtures.ae();
        AE2Controller.AE2Interface = processInterface;

        AE2Controller.startHTTPServer();
        String token = login();
        Response firstWorld = performSyncedRequest(token);
        assertEquals(200, firstWorld.status);
        assertTrue(firstWorld.body.contains("\"status\":\"OK\""));

        CoreEngine.onServerStopping();
        CoreEngine.onServerStopped();

        assertSame(processInterface, AE2Controller.AE2Interface);
        AE2Controller.startHTTPServer();

        Response secondWorld = get("/grids", token);
        assertEquals(401, secondWorld.status, "a token issued for the old world must no longer authorize");

        String secondWorldToken = login();
        Response secondWorldAuthorized = performSyncedRequest(secondWorldToken);
        assertEquals(200, secondWorldAuthorized.status);
        assertTrue(secondWorldAuthorized.body.contains("\"status\":\"OK\""));
    }

    @Test
    void pendingRegistrationLookupIsRejectedWhenTheServerStops() throws Exception {
        BlockingPlayerLookup platform = new BlockingPlayerLookup(
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        AE2Controller.serverPlatform = platform;
        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange("register=Player&password=test-password");
        ExecutorService oldWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> oldRequest = oldWorker.submit(() -> {
                try {
                    new AE2Controller.AuthHandler().handle(exchange);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (AE2Controller.requests.isEmpty() && !oldRequest.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertFalse(oldRequest.isDone(), "the worker should be waiting for the queued lookup");

            CoreEngine.onServerStopping();
            oldRequest.get(5, TimeUnit.SECONDS);

            assertEquals(503, exchange.responseCode, "stopping must release a worker waiting for PlayerList");
            assertEquals(1L, platform.entered.getCount(), "shutdown must not touch live PlayerList state");
        } finally {
            platform.release.countDown();
            oldWorker.shutdownNow();
        }
    }

    @Test
    void registrationLookupTimeoutReturnsServiceUnavailableInsteadOfNotOnline() throws Exception {
        AtomicInteger playerListLookups = new AtomicInteger();
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public UUID getOnlinePlayerUUID(String username) {
                playerListLookups.incrementAndGet();
                return null;
            }

            @Override
            public File getConfigDirectory() {
                return tempDirectory;
            }
        };
        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange("register=Player&password=test-password");

        new AE2Controller.AuthHandler().handle(exchange);

        assertEquals(503, exchange.responseCode);
        assertEquals("SERVER_BUSY", exchange.responseBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals(0, playerListLookups.get(), "a timed-out queued lookup must not run later");
        assertTrue(AE2Controller.requests.isEmpty());
    }

    @Test
    void registrationFailsFastWhenTheServerThreadQueueIsFull() throws Exception {
        AE2Controller.startHTTPServer();
        fillServerThreadQueue();
        PostExchange exchange = new PostExchange("register=Player&password=test-password");

        assertTimeout(Duration.ofSeconds(1), () -> new AE2Controller.AuthHandler().handle(exchange));

        assertEquals(503, exchange.responseCode);
        assertEquals("SERVER_BUSY", exchange.responseBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals(32, AE2Controller.requests.size());
    }

    @Test
    void syncedRequestReturnsServiceUnavailableWhenTheServerThreadQueueIsFull() throws Exception {
        AE2Controller.startHTTPServer();
        String token = login();
        fillServerThreadQueue();

        Response response = get("/grids", token);

        assertEquals(503, response.status);
        assertTrue(response.body.contains("\"status\":\"SERVER_BUSY\""));
        assertEquals(32, AE2Controller.requests.size());
    }

    @Test
    void registrationReportsNotOnlineOnlyAfterTheServerThreadChecksTheLivePlayerList() throws Exception {
        AtomicInteger playerListLookups = new AtomicInteger();
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public UUID getOnlinePlayerUUID(String username) {
                playerListLookups.incrementAndGet();
                return null;
            }

            @Override
            public File getConfigDirectory() {
                return tempDirectory;
            }
        };
        AE2Controller.AE2Interface = TestGridFixtures.ae();
        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange("register=MissingPlayer&password=test-password");
        ExecutorService httpWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> request = httpWorker.submit(() -> {
                try {
                    new AE2Controller.AuthHandler().handle(exchange);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (AE2Controller.requests.isEmpty() && !request.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertFalse(request.isDone(), "HTTP must wait for the server-thread lookup");
            assertEquals(0, playerListLookups.get(), "the HTTP worker must not inspect PlayerList");

            CoreEngine.onServerTick();
            request.get(2, TimeUnit.SECONDS);

            assertEquals(1, playerListLookups.get());
            assertEquals(400, exchange.responseCode);
            assertEquals("notonline", exchange.responseBody.toString(StandardCharsets.UTF_8.name()));
        } finally {
            httpWorker.shutdownNow();
        }
    }

    @Test
    void registrationFormPreservesTheNotOnlineRedirectAfterTheServerThreadLookup() throws Exception {
        AtomicInteger playerListLookups = new AtomicInteger();
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public UUID getOnlinePlayerUUID(String username) {
                playerListLookups.incrementAndGet();
                return null;
            }

            @Override
            public File getConfigDirectory() {
                return tempDirectory;
            }
        };
        AE2Controller.AE2Interface = TestGridFixtures.ae();
        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange("register=MissingPlayer&password=test-password");
        ExecutorService httpWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> request = httpWorker.submit(() -> {
                try {
                    new AE2Controller.WebHandler().handle(exchange);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (AE2Controller.requests.isEmpty() && !request.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertFalse(request.isDone());

            CoreEngine.onServerTick();
            request.get(2, TimeUnit.SECONDS);

            assertEquals(1, playerListLookups.get());
            assertEquals(302, exchange.responseCode);
            assertEquals(
                "?notonline",
                exchange.getResponseHeaders()
                    .getFirst("Location"));
        } finally {
            httpWorker.shutdownNow();
        }
    }

    @Test
    void publicLoginUsesStoredIdentityWithoutConsultingServerState() throws Exception {
        UUID playerUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        BlockingPlayerLookup platform = new BlockingPlayerLookup(playerUuid);
        AtomicInteger aePlayerLookups = new AtomicInteger();
        AE2Controller.serverPlatform = platform;
        ConfigBootstrap.aePublicModeValue = () -> true;
        AE2Controller.AE2Interface = new TestGridFixtures.TestAE() {

            @Override
            public int web$getPlayerId(PlayerIdentity identity) {
                aePlayerLookups.incrementAndGet();
                return 42;
            }
        };
        CoreDataTestFixture.reset();
        assertTrue(
            CoreData.setPassword(
                new PlayerIdentity(playerUuid, "Player"),
                PasswordHelper.generateStrongPasswordHash("player-password")));

        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange("username=Player&password=player-password");
        ExecutorService oldWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> oldRequest = oldWorker.submit(() -> {
                try {
                    new AE2Controller.AuthHandler().handle(exchange);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            oldRequest.get(2, TimeUnit.SECONDS);

            assertEquals(200, exchange.responseCode);
            JsonObject response = new Gson()
                .fromJson(exchange.responseBody.toString(StandardCharsets.UTF_8.name()), JsonObject.class);
            assertFalse(
                response.get("isAdmin")
                    .getAsBoolean(),
                "a player account must never inherit the old admin sentinel");
            assertEquals(
                "Player",
                response.get("username")
                    .getAsString());
            assertEquals(1L, platform.entered.getCount(), "login must use the account name stored by CoreData");
            assertEquals(0, aePlayerLookups.get(), "login must not touch world-scoped AE2 player data");
        } finally {
            platform.release.countDown();
            oldWorker.shutdownNow();
        }
    }

    @Test
    void aePlayerIdIsResolvedByTheFirstSyncedRequestAndReusedByTheNextOne() throws Exception {
        UUID playerUuid = UUID.fromString("22222222-3333-4444-5555-666666666666");
        AtomicInteger aePlayerLookups = new AtomicInteger();
        ConfigBootstrap.aePublicModeValue = () -> true;
        AE2Controller.AE2Interface = new TestGridFixtures.TestAE() {

            @Override
            public int web$getPlayerId(PlayerIdentity identity) {
                aePlayerLookups.incrementAndGet();
                return playerUuid.equals(identity.uuid) ? 42 : -1;
            }
        };
        CoreDataTestFixture.reset();
        CoreData.setPassword(
            new PlayerIdentity(playerUuid, "Player"),
            PasswordHelper.generateStrongPasswordHash("player-password"));

        AE2Controller.startHTTPServer();
        String token = login("Player", "player-password");

        assertEquals(0, aePlayerLookups.get(), "login must remain independent of the server tick");
        assertEquals(200, performSyncedRequest(token).status);
        assertEquals(1, aePlayerLookups.get());
        assertEquals(200, performSyncedRequest(token).status);
        assertEquals(1, aePlayerLookups.get(), "the cached AE2 id must be reused before half life");
    }

    @Test
    void authenticatedPageUsesTheAccountNameWithoutReadingTheAeProfile() throws Exception {
        UUID playerUuid = UUID.fromString("99999999-8888-7777-6666-555555555555");
        ConfigBootstrap.aePublicModeValue = () -> true;
        AE2Controller.AE2Interface = new TestGridFixtures.TestAE() {

            @Override
            public int web$getPlayerId(PlayerIdentity identity) {
                return playerUuid.equals(identity.uuid) ? 42 : -1;
            }

            @Override
            public PlayerIdentity web$getPlayerProfile(int playerId) {
                throw new AssertionError("authenticated HTTP must not read an AE2 profile");
            }
        };
        CoreDataTestFixture.reset();
        assertTrue(
            CoreData.setPassword(
                new PlayerIdentity(playerUuid, "CanonicalPlayer"),
                PasswordHelper.generateStrongPasswordHash("player-password")));

        AE2Controller.startHTTPServer();
        String token = login("canonicalplayer", "player-password");
        Response page = get("/", token);

        assertEquals(200, page.status);
        assertTrue(page.body.contains("CanonicalPlayer"));
    }

    private String login() throws IOException {
        return login("admin", "lifecycle-password");
    }

    private static void fillServerThreadQueue() {
        for (int i = 0; i < 32; i++) {
            assertTrue(AE2Controller.requests.offer(new IServerThreadTask() {

                @Override
                public void runOnServerThread(IAE ae) {}

                @Override
                public void failIfPending(String status) {}
            }));
        }
    }

    private String login(String username, String password) throws IOException {
        byte[] body = ("username=" + username + "&password=" + password).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = connection("/auth", null);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        Response response = read(connection);
        assertEquals(200, response.status);
        JsonObject json = new Gson().fromJson(response.body, JsonObject.class);
        return json.get("token")
            .getAsString();
    }

    private Response performSyncedRequest(String token) throws Exception {
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            Future<Response> response = client.submit(() -> get("/grids", token));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (AE2Controller.requests.isEmpty() && !response.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertFalse(AE2Controller.requests.isEmpty(), "the authenticated request should reach the tick queue");
            CoreEngine.onServerTick();
            return response.get(3, TimeUnit.SECONDS);
        } finally {
            client.shutdownNow();
        }
    }

    private Response get(String path, String token) throws IOException {
        return read(connection(path, token));
    }

    private HttpURLConnection connection(String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path)
            .toURL()
            .openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(3_000);
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private static Response read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return new Response(status, "");
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new Response(status, new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static int unusedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
