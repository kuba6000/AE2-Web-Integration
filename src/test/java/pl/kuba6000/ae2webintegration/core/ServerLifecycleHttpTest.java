package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
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
        public UUID getRegisteredPlayerUUID(String username) {
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
        previousConfigDirectory = Config.getConfigDirectory();
        previousServerPlatform = AE2Controller.serverPlatform;

        port = unusedLoopbackPort();
        ConfigBootstrap.aePortValue = () -> port;
        ConfigBootstrap.aePasswordValue = () -> "lifecycle-password";
        ConfigBootstrap.allowNoPasswordOnLocalhostValue = () -> false;
        ConfigBootstrap.aePublicModeValue = () -> false;
        Config.init(tempDirectory);
    }

    @AfterEach
    void tearDown() {
        CoreEngine.onServerStopped();
        ConfigBootstrap.aePortValue = previousPort;
        ConfigBootstrap.aePasswordValue = previousPassword;
        ConfigBootstrap.allowNoPasswordOnLocalhostValue = previousLocalAccess;
        ConfigBootstrap.aePublicModeValue = previousPublicMode;
        AE2Controller.serverPlatform = previousServerPlatform;
        if (previousConfigDirectory != null) {
            Config.init(previousConfigDirectory.getParentFile());
        }
    }

    @Test
    void secondServerLifecycleRebindsRejectsTheOldTokenAndServesANewSession() throws Exception {
        IAE processInterface = TestGridFixtures.ae();
        CoreData processAccounts = new CoreData();
        AE2Controller.AE2Interface = processInterface;
        CoreData.instance = processAccounts;

        AE2Controller.startHTTPServer();
        String token = login();
        Response firstWorld = performSyncedRequest(token);
        assertEquals(200, firstWorld.status);
        assertTrue(firstWorld.body.contains("\"status\":\"OK\""));

        CoreEngine.onServerStopping();
        CoreEngine.onServerStopped();

        assertSame(processInterface, AE2Controller.AE2Interface);
        assertSame(processAccounts, CoreData.instance);
        AE2Controller.startHTTPServer();

        Response secondWorld = get("/grids", token);
        assertEquals(401, secondWorld.status, "a token issued for the old world must no longer authorize");

        String secondWorldToken = login();
        Response secondWorldAuthorized = performSyncedRequest(secondWorldToken);
        assertEquals(200, secondWorldAuthorized.status);
        assertTrue(secondWorldAuthorized.body.contains("\"status\":\"OK\""));
    }

    @Test
    void registrationFinishingAfterAWorldSwitchIsRejected() throws Exception {
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
            assertTrue(platform.entered.await(2, TimeUnit.SECONDS), "the old request should reach player lookup");

            CoreEngine.onServerStopping();
            CoreEngine.onServerStopped();
            AE2Controller.startHTTPServer();
            platform.release.countDown();
            oldRequest.get(5, TimeUnit.SECONDS);

            assertEquals(
                503,
                exchange.responseCode,
                "a request from the stopped lifecycle must not publish auth state");
        } finally {
            platform.release.countDown();
            oldWorker.shutdownNow();
        }
    }

    @Test
    void loginFinishingAfterAWorldSwitchIsRejected() throws Exception {
        UUID playerUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        BlockingPlayerLookup platform = new BlockingPlayerLookup(playerUuid);
        AE2Controller.serverPlatform = platform;
        ConfigBootstrap.aePublicModeValue = () -> true;
        AE2Controller.AE2Interface = new TestGridFixtures.TestAE() {

            @Override
            public int web$getPlayerId(PlayerIdentity identity) {
                return 42;
            }
        };
        CoreData.instance = new CoreData();
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
            assertTrue(platform.entered.await(2, TimeUnit.SECONDS), "the old request should reach player lookup");

            CoreEngine.onServerStopping();
            CoreEngine.onServerStopped();
            AE2Controller.startHTTPServer();
            platform.release.countDown();
            oldRequest.get(5, TimeUnit.SECONDS);

            assertEquals(503, exchange.responseCode, "a login from the stopped lifecycle must not publish a token");
        } finally {
            platform.release.countDown();
            oldWorker.shutdownNow();
        }
    }

    private String login() throws IOException {
        byte[] body = "username=admin&password=lifecycle-password".getBytes(StandardCharsets.UTF_8);
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
