package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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

import com.github.bsideup.jabel.Desugar;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.commands.CommandProcessor;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.config.CoreDataTestFixture;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.WebHandler;
import pl.kuba6000.ae2webintegration.core.http.endpoint.auth.Login;
import pl.kuba6000.ae2webintegration.core.http.endpoint.auth.Register;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class ServerLifecycleHttpTest {

    @Desugar
    private record Response(int status, String body) {}

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
        public ILegacyConfigProvider getLegacyConfig() {
            return null;
        }

        @Override
        public File getConfigDirectory() {
            return null;
        }

        @Override
        public File getWorldDirectory() {
            throw new AssertionError("This lookup-only fixture has no world lifecycle");
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

    private static class PostExchange extends TestGridFixtures.TestExchange {

        private final byte[] requestBody;
        private final URI uri;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private volatile int responseCode = -1;

        private PostExchange(String requestBody) {
            this("/", requestBody);
        }

        private PostExchange(String path, String requestBody) {
            super(null);
            uri = URI.create(path);
            this.requestBody = requestBody.getBytes(StandardCharsets.UTF_8);
            getRequestHeaders().set(
                "Content-Type",
                path.startsWith("/api/") ? "application/json" : "application/x-www-form-urlencoded");
        }

        @Override
        public String getRequestMethod() {
            return "POST";
        }

        @Override
        public URI getRequestURI() {
            return uri;
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

    private ConfigTestFixture config;
    private IServerPlatform previousServerPlatform;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        CoreEngine.onServerStopped();
        previousServerPlatform = AE2Controller.serverPlatform;

        port = unusedLoopbackPort();
        config = new ConfigTestFixture(tempDirectory);
        config.write("general.port", port);
        config.write("general.password", "lifecycle-password");
        config.write("general.allow_no_password_on_localhost", false);
        config.write("general.public_mode", false);
        config.write("general.check_for_updates", false);
        Config.reload();
    }

    @AfterEach
    void tearDown() {
        CoreEngine.onServerStopped();
        AE2Controller.serverPlatform = previousServerPlatform;
        config.close();
    }

    @Test
    void failedConfigReloadKeepsTheListenerAndAuthenticatedSessionWorking() throws Exception {
        startApi();
        String token = login();
        assertEquals(HttpURLConnection.HTTP_OK, performSyncedRequest(token).status());

        config.writeRaw("[general\nport = 2324");
        assertFalse(
            CommandProcessor.reload()
                .isSuccess());

        assertEquals(port, Config.INSTANCE.general.port);
        assertEquals("lifecycle-password", Config.INSTANCE.general.password);
        assertFalse(Config.INSTANCE.general.publicMode);
        assertFalse(Config.INSTANCE.general.allowNoPasswordOnLocalhost);
        assertEquals(HttpURLConnection.HTTP_OK, performSyncedRequest(token).status());
    }

    @Test
    void secondServerLifecycleRebindsRejectsTheOldTokenAndServesANewSession() throws Exception {
        IAE processInterface = TestGridFixtures.ae();
        AE2Controller.AE2Interface = processInterface;

        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
        String token = login();
        Response firstWorld = performSyncedRequest(token);
        assertEquals(HttpURLConnection.HTTP_OK, firstWorld.status());
        assertTrue(
            firstWorld.body()
                .contains("\"status\":\"OK\""));

        CoreEngine.onServerStopping();
        CoreEngine.onServerStopped();

        assertSame(processInterface, AE2Controller.AE2Interface);
        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();

        Response secondWorld = get("/api/grids", token);
        assertEquals(
            HttpURLConnection.HTTP_UNAUTHORIZED,
            secondWorld.status(),
            "a token issued for the old world must no longer authorize");

        String secondWorldToken = login();
        Response secondWorldAuthorized = performSyncedRequest(secondWorldToken);
        assertEquals(HttpURLConnection.HTTP_OK, secondWorldAuthorized.status());
        assertTrue(
            secondWorldAuthorized.body()
                .contains("\"status\":\"OK\""));
    }

    @Test
    // Java 8 HttpExchange is not AutoCloseable; the fake's close() is a no-op.
    @SuppressWarnings({ "BusyWait", "resource" }) // Queue polling is bounded by a deadline.
    void pendingRegistrationLookupIsRejectedWhenTheServerStops() throws Exception {
        BlockingPlayerLookup platform = new BlockingPlayerLookup(
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        AE2Controller.serverPlatform = platform;
        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange(
            "/api/auth/register",
            "{\"username\":\"Player\",\"password\":\"test-password\"}");
        ExecutorService oldWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> oldRequest = oldWorker.submit(() -> {
                try {
                    authRouter().handle(exchange);
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

            assertEquals(
                HttpURLConnection.HTTP_UNAVAILABLE,
                exchange.responseCode,
                "stopping must release a worker waiting for PlayerList");
            assertEquals("SERVER_STOPPING", responseStatus(exchange));
            assertEquals(1L, platform.entered.getCount(), "shutdown must not touch live PlayerList state");
        } finally {
            platform.release.countDown();
            oldWorker.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("CharsetObjectCanBeUsed") // The Charset overload requires Java 10; tests also target Java 8.
    void registrationLookupTimeoutReturnsServiceUnavailableInsteadOfNotOnline() throws Exception {
        AtomicInteger playerListLookups = new AtomicInteger();
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public UUID getOnlinePlayerUUID(String username) {
                playerListLookups.incrementAndGet();
                return null;
            }

            @Override
            public ILegacyConfigProvider getLegacyConfig() {
                return null;
            }

            @Override
            public File getConfigDirectory() {
                return tempDirectory;
            }

            @Override
            public File getWorldDirectory() {
                return new File(tempDirectory, "test-save");
            }
        };
        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange(
            "/api/auth/register",
            "{\"username\":\"Player\",\"password\":\"test-password\"}");

        authRouter().handle(exchange);

        assertEquals(HttpURLConnection.HTTP_UNAVAILABLE, exchange.responseCode);
        assertEquals("SERVER_BUSY", responseStatus(exchange));
        assertEquals(0, playerListLookups.get(), "a timed-out queued lookup must not run later");
        assertTrue(AE2Controller.requests.isEmpty());
    }

    @Test
    // Java 8 has neither the Charset overload nor AutoCloseable HttpExchange; the fake close() is a no-op.
    @SuppressWarnings({ "CharsetObjectCanBeUsed", "resource" })
    void registrationFailsFastWhenTheServerThreadQueueIsFull() throws Exception {
        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
        fillServerThreadQueue();
        PostExchange exchange = new PostExchange(
            "/api/auth/register",
            "{\"username\":\"Player\",\"password\":\"test-password\"}");

        assertTimeout(Duration.ofSeconds(1), () -> authRouter().handle(exchange));

        assertEquals(HttpURLConnection.HTTP_UNAVAILABLE, exchange.responseCode);
        assertEquals("SERVER_BUSY", responseStatus(exchange));
        assertEquals(32, AE2Controller.requests.size());
    }

    @Test
    void syncedRequestReturnsServiceUnavailableWhenTheServerThreadQueueIsFull() throws Exception {
        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
        String token = login();
        fillServerThreadQueue();

        Response response = get("/api/grids", token);

        assertEquals(HttpURLConnection.HTTP_UNAVAILABLE, response.status());
        assertTrue(
            response.body()
                .contains("\"status\":\"SERVER_BUSY\""));
        assertEquals(32, AE2Controller.requests.size());
    }

    @Test
    // Keep Java 8 charset/HttpExchange APIs; the fake close() is a no-op and queue polling has a deadline.
    @SuppressWarnings({ "BusyWait", "CharsetObjectCanBeUsed", "resource" })
    void registrationReportsNotOnlineOnlyAfterTheServerThreadChecksTheLivePlayerList() throws Exception {
        AtomicInteger playerListLookups = new AtomicInteger();
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public UUID getOnlinePlayerUUID(String username) {
                playerListLookups.incrementAndGet();
                return null;
            }

            @Override
            public ILegacyConfigProvider getLegacyConfig() {
                return null;
            }

            @Override
            public File getConfigDirectory() {
                return tempDirectory;
            }

            @Override
            public File getWorldDirectory() {
                return new File(tempDirectory, "test-save");
            }
        };
        AE2Controller.AE2Interface = TestGridFixtures.ae();
        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange(
            "/api/auth/register",
            "{\"username\":\"MissingPlayer\",\"password\":\"test-password\"}");
        ExecutorService httpWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> request = httpWorker.submit(() -> {
                try {
                    authRouter().handle(exchange);
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
            assertEquals(HttpURLConnection.HTTP_CONFLICT, exchange.responseCode);
            assertEquals("NOT_ONLINE", responseStatus(exchange));
        } finally {
            httpWorker.shutdownNow();
        }
    }

    @Test
    // Java 8 HttpExchange is not AutoCloseable; the fake's close() is a no-op.
    @SuppressWarnings({ "BusyWait", "resource" }) // Queue polling is bounded by a deadline.
    void registrationFormPreservesTheNotOnlineRedirectAfterTheServerThreadLookup() throws Exception {
        AtomicInteger playerListLookups = new AtomicInteger();
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public UUID getOnlinePlayerUUID(String username) {
                playerListLookups.incrementAndGet();
                return null;
            }

            @Override
            public ILegacyConfigProvider getLegacyConfig() {
                return null;
            }

            @Override
            public File getConfigDirectory() {
                return tempDirectory;
            }

            @Override
            public File getWorldDirectory() {
                return new File(tempDirectory, "test-save");
            }
        };
        AE2Controller.AE2Interface = TestGridFixtures.ae();
        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange("register=MissingPlayer&password=test-password");
        ExecutorService httpWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> request = httpWorker.submit(() -> {
                try {
                    new WebHandler().handle(exchange);
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
            assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, exchange.responseCode);
            assertEquals(
                "?NOT_ONLINE",
                exchange.getResponseHeaders()
                    .getFirst("Location"));
        } finally {
            httpWorker.shutdownNow();
        }
    }

    @Test
    // Java 8 has neither the Charset overload nor AutoCloseable HttpExchange; the fake close() is a no-op.
    @SuppressWarnings({ "CharsetObjectCanBeUsed", "resource" })
    void publicLoginUsesStoredIdentityWithoutConsultingServerState() throws Exception {
        UUID playerUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        BlockingPlayerLookup platform = new BlockingPlayerLookup(playerUuid);
        AE2Controller.serverPlatform = platform;
        config.set("general.public_mode", true);
        AE2Controller.AE2Interface = null;
        CoreDataTestFixture.reset();
        assertTrue(
            CoreData.setPassword(
                new PlayerIdentity(playerUuid, "Player"),
                PasswordHelper.generateStrongPasswordHash("player-password")));

        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
        PostExchange exchange = new PostExchange(
            "/api/auth/login",
            "{\"username\":\"Player\",\"password\":\"player-password\"}");
        ExecutorService oldWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> oldRequest = oldWorker.submit(() -> {
                try {
                    authRouter().handle(exchange);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            oldRequest.get(2, TimeUnit.SECONDS);

            assertEquals(HttpURLConnection.HTTP_OK, exchange.responseCode);
            JsonObject response = new Gson()
                .fromJson(exchange.responseBody.toString(StandardCharsets.UTF_8.name()), JsonObject.class)
                .getAsJsonObject("data");
            assertFalse(
                response.get("isAdmin")
                    .getAsBoolean(),
                "a player account must never inherit the old admin sentinel");
            assertEquals(
                "Player",
                response.get("username")
                    .getAsString());
            assertEquals(1L, platform.entered.getCount(), "login must use the account name stored by CoreData");
        } finally {
            platform.release.countDown();
            oldWorker.shutdownNow();
        }
    }

    @Test
    void authenticatedPageUsesTheAccountNameWithoutReadingTheAeProfile() throws Exception {
        UUID playerUuid = UUID.fromString("99999999-8888-7777-6666-555555555555");
        config.set("general.public_mode", true);
        AE2Controller.AE2Interface = null;
        CoreDataTestFixture.reset();
        assertTrue(
            CoreData.setPassword(
                new PlayerIdentity(playerUuid, "CanonicalPlayer"),
                PasswordHelper.generateStrongPasswordHash("player-password")));

        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
        String token = login("canonicalplayer", "player-password");
        Response page = get("/", token);

        assertEquals(HttpURLConnection.HTTP_OK, page.status());
        assertTrue(
            page.body()
                .contains("CanonicalPlayer"));
    }

    @Test
    void apiGridListingAcceptsBearerAndReturnsJson() throws Exception {
        startApi();
        String token = login();
        HttpURLConnection connection = connection("/api/grids", token);
        Response response = performSyncedRequest(() -> read(connection));
        assertEquals(HttpURLConnection.HTTP_OK, response.status());
        assertTrue(
            connection.getHeaderField("Content-Type")
                .startsWith("application/json"));
        JsonObject body = new Gson().fromJson(response.body(), JsonObject.class);
        assertEquals(
            "OK",
            body.get("status")
                .getAsString());
        assertEquals(
            0,
            body.getAsJsonArray("data")
                .size());
    }

    @Test
    void apiCookieReadDoesNotPerformLegacyLogoutOrParseLegacyGridParameter() throws Exception {
        startApi();
        String token = login();
        HttpURLConnection connection = connection("/api/grids?logout&grid=not-a-key", null);
        connection.setRequestProperty("Cookie", "other=value;authenticationToken=" + token);
        Response response = performSyncedRequest(() -> read(connection));
        assertEquals(HttpURLConnection.HTTP_OK, response.status());
        assertEquals(HttpURLConnection.HTTP_OK, performSyncedRequest(() -> get("/api/grids", token)).status());
    }

    @Test
    void cookieLogoutRequiresAMutationMarkerAndRevokesOnlyAfterPost() throws Exception {
        startApi();
        String token = login();
        HttpURLConnection unmarked = connection("/api/auth/logout", null);
        unmarked.setRequestMethod("POST");
        unmarked.setRequestProperty("Cookie", "authenticationToken=" + token);
        assertEquals(HttpURLConnection.HTTP_FORBIDDEN, read(unmarked).status());
        assertEquals(HttpURLConnection.HTTP_OK, performSyncedRequest(token).status());

        HttpURLConnection marked = connection("/api/auth/logout", null);
        marked.setRequestMethod("POST");
        marked.setRequestProperty("Cookie", "authenticationToken=" + token);
        marked.setRequestProperty("X-AE2-Request", "true");
        Response logout = read(marked);
        assertEquals(HttpURLConnection.HTTP_OK, logout.status());
        JsonObject envelope = new Gson().fromJson(logout.body(), JsonObject.class);
        assertEquals(
            "OK",
            envelope.get("status")
                .getAsString());
        assertTrue(
            envelope.get("data")
                .isJsonNull());
        assertNull(marked.getHeaderField("Set-Cookie"), "nested API routes must not install page cookies");
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, get("/api/grids", token).status());
    }

    @Test
    void bearerLogoutNeedsNoBrowserMarkerAndLegacyLogoutCannotRevoke() throws Exception {
        startApi();
        String token = login();
        for (String path : new String[] { "/auth", "/auth?revoke", "/grids" }) {
            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, get(path, token).status());
        }
        assertEquals(HttpURLConnection.HTTP_OK, get("/?logout", token).status());
        assertEquals(HttpURLConnection.HTTP_OK, performSyncedRequest(token).status());
        assertEquals(HttpURLConnection.HTTP_BAD_METHOD, get("/api/auth/logout", token).status());
        HttpURLConnection logout = connection("/api/auth/logout", token);
        logout.setRequestMethod("POST");
        assertEquals(HttpURLConnection.HTTP_OK, read(logout).status());
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, get("/api/grids", token).status());
    }

    @Test
    void registrationReturnsAnAcceptedTokenWithoutInstallingACookie() throws Exception {
        UUID player = UUID.fromString("12121212-3434-5656-7878-909090909090");
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public UUID getOnlinePlayerUUID(String username) {
                return player;
            }

            @Override
            public ILegacyConfigProvider getLegacyConfig() {
                return null;
            }

            @Override
            public File getConfigDirectory() {
                return tempDirectory;
            }

            @Override
            public File getWorldDirectory() {
                return new File(tempDirectory, "test-save");
            }
        };
        startApi();
        HttpURLConnection registration = jsonPost(
            "/api/auth/register",
            null,
            "{\"username\":\"Player\",\"password\":\"player-password\"}");
        Response response = performSyncedRequest(() -> read(registration));
        assertEquals(HttpURLConnection.HTTP_ACCEPTED, response.status());
        JsonObject envelope = new Gson().fromJson(response.body(), JsonObject.class);
        assertEquals(
            "OK",
            envelope.get("status")
                .getAsString());
        assertFalse(
            envelope.getAsJsonObject("data")
                .get("token")
                .getAsString()
                .isEmpty());
        assertNull(registration.getHeaderField("Set-Cookie"));
    }

    @Test
    void jsonLoginRetainsItsDomainFailureCode() throws Exception {
        startApi();
        Response response = read(
            jsonPost("/api/auth/login", null, "{\"username\":\"admin\",\"password\":\"incorrect\"}"));
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.status());
        assertEquals(
            "INVALID_PASSWORD",
            new Gson().fromJson(response.body(), JsonObject.class)
                .get("status")
                .getAsString());
        config.set("general.public_mode", true);
        CoreDataTestFixture.reset();
        Response missing = read(
            jsonPost("/api/auth/login", null, "{\"username\":\"MissingPlayer\",\"password\":\"incorrect\"}"));
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, missing.status());
        assertEquals(
            "INVALID_USER",
            new Gson().fromJson(missing.body(), JsonObject.class)
                .get("status")
                .getAsString());
    }

    @Test
    void apiRejectsInvalidExplicitCredentialsWithoutCookieFallback() throws Exception {
        startApi();
        String token = login();
        for (String authorization : new String[] { "Bearer invalid", token, "Basic " + token }) {
            HttpURLConnection connection = connection("/api/grids", null);
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("Cookie", "authenticationToken=" + token);
            Response response = read(connection);
            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.status());
            assertEquals(
                "UNAUTHORIZED",
                new Gson().fromJson(response.body(), JsonObject.class)
                    .get("status")
                    .getAsString());
            assertEquals("Bearer", connection.getHeaderField("WWW-Authenticate"));
        }
    }

    @Test
    void apiRoutingRejectsUnknownPathsAndUnsupportedMethods() throws Exception {
        startApi();
        for (String path : new String[] { "/api/grids/extra", "/api/gridsXYZ", "/api/openapi.json" }) {
            Response response = get(path, null);
            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, response.status());
            assertEquals(
                "NOT_FOUND",
                new Gson().fromJson(response.body(), JsonObject.class)
                    .get("status")
                    .getAsString());
        }
        HttpURLConnection connection = connection("/api/grids", null);
        connection.setRequestMethod("POST");
        Response response = read(connection);
        assertEquals(HttpURLConnection.HTTP_BAD_METHOD, response.status());
        assertTrue(
            connection.getHeaderField("Allow")
                .contains("GET"));
        assertEquals(
            "METHOD_NOT_ALLOWED",
            new Gson().fromJson(response.body(), JsonObject.class)
                .get("status")
                .getAsString());
    }

    @Test
    void apiReportsQueueSaturationAndHandlerFailureWithHttpErrors() throws Exception {
        startApi();
        String token = login();
        fillServerThreadQueue();
        Response busy = get("/api/grids", token);
        assertEquals(HttpURLConnection.HTTP_UNAVAILABLE, busy.status());
        assertEquals(
            "SERVER_BUSY",
            new Gson().fromJson(busy.body(), JsonObject.class)
                .get("status")
                .getAsString());
        CoreEngine.onServerTick();
        CoreEngine.GRID_IDENTITIES.clear();
        Response failed = performSyncedRequest(() -> get("/api/grids", token));
        assertEquals(HttpURLConnection.HTTP_INTERNAL_ERROR, failed.status());
        assertEquals(
            "INTERNAL_ERROR",
            new Gson().fromJson(failed.body(), JsonObject.class)
                .get("status")
                .getAsString());
    }

    @Test
    void websiteLoginUsesTrustedProxySchemeAndPublicHostWithoutFetchMetadata() throws Exception {
        startApi();
        String[][] origins = { { "https://terminal.example", "terminal.example", "https", "302" },
            { "https://terminal.example:8443", "terminal.example:8443", "https", "302" },
            { "http://terminal.example", "terminal.example", "http", "302" },
            { "http://terminal.example", "terminal.example", null, "302" },
            { "https://terminal.example", "terminal.example", null, "403" },
            { "https://other.example", "terminal.example", "https", "403" },
            { "https://terminal.example:8443", "terminal.example", "https", "403" },
            { "http://terminal.example", "terminal.example", "https", "403" },
            { "https://terminal.example", "terminal.example", "https, http", "403" },
            { "https://terminal.example", "terminal.example", "https\r\nX-Forwarded-Proto: https", "403" },
            { "https://terminal.example", "terminal.example", "invalid", "403" },
            { "https://terminal.example/path", "terminal.example", "https", "403" },
            { "https://user@terminal.example", "terminal.example", "https", "403" },
            { "null", "terminal.example", "https", "403" } };
        for (String[] scenario : origins) {
            // A raw HTTP request preserves Host and Origin, which HttpURLConnection restricts.
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                String body = "username=admin&password=lifecycle-password";
                String request = "POST / HTTP/1.1\r\nHost: " + scenario[1]
                    + "\r\nOrigin: "
                    + scenario[0]
                    + (scenario[2] == null ? "" : "\r\nX-Forwarded-Proto: " + scenario[2])
                    + "\r\nContent-Type: application/x-www-form-urlencoded\r\nConnection: close\r\nContent-Length: "
                    + body.length()
                    + "\r\n\r\n"
                    + body;
                socket.getOutputStream()
                    .write(request.getBytes(StandardCharsets.US_ASCII));
                BufferedReader response = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                assertEquals(
                    scenario[3],
                    response.readLine()
                        .split(" ")[1],
                    scenario[0] + " via " + scenario[2]);
            }
        }
    }

    @Test
    void websiteLoginIgnoresForwardedSchemeFromUntrustedConnections() throws Exception {
        config.set("general.trusted_proxies", "192.0.2.11");
        startApi();
        for (String peer : new String[] { "192.0.2.10", "192.0.2.11" }) {
            PostExchange exchange = new PostExchange("username=admin&password=lifecycle-password") {

                @Override
                public InetSocketAddress getRemoteAddress() {
                    return new InetSocketAddress(peer, 12345);
                }
            };
            exchange.getRequestHeaders()
                .set("Host", "terminal.example");
            exchange.getRequestHeaders()
                .set("Origin", "https://terminal.example");
            exchange.getRequestHeaders()
                .set("X-Forwarded-Proto", "https");
            new WebHandler().handle(exchange);
            assertEquals(
                peer.equals("192.0.2.11") ? HttpURLConnection.HTTP_MOVED_TEMP : HttpURLConnection.HTTP_FORBIDDEN,
                exchange.responseCode,
                peer);
        }
    }

    @Test
    void websiteLoginCookieCoversApiPathsAndRemainsHttpOnly() throws Exception {
        startApi();
        HttpURLConnection connection = connection("/", null);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        byte[] body = "username=admin&password=lifecycle-password".getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, read(connection).status());
        String cookie = connection.getHeaderField("Set-Cookie");
        assertFalse(
            cookie.toLowerCase()
                .contains("path="));
        assertTrue(cookie.contains("HttpOnly"));
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        cookies.put(
            URI.create("https://terminal.example/ae2/"),
            Collections.singletonMap("Set-Cookie", Collections.singletonList(cookie)));
        Map<String, List<String>> scoped = cookies
            .get(URI.create("https://terminal.example/ae2/api/grids"), Collections.emptyMap());
        assertTrue(
            scoped.get("Cookie")
                .stream()
                .anyMatch(value -> value.contains("authenticationToken=")));
        Map<String, List<String>> unrelated = cookies
            .get(URI.create("https://terminal.example/other/api/grids"), Collections.emptyMap());
        assertTrue(
            unrelated.getOrDefault("Cookie", Collections.emptyList())
                .isEmpty());
        HttpURLConnection api = connection("/api/grids", null);
        api.setRequestProperty("Cookie", cookie.split(";", 2)[0]);
        assertEquals(HttpURLConnection.HTTP_OK, performSyncedRequest(() -> read(api)).status());
    }

    @Test
    void apiHeadAndPreflightAdvertiseOnlySupportedMethods() throws Exception {
        startApi();
        String token = login();
        HttpURLConnection preflight = connection("/api/grids", null);
        preflight.setRequestMethod("OPTIONS");
        preflight.setRequestProperty("Origin", "https://example.org");
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, read(preflight).status());
        assertTrue(
            preflight.getHeaderField("Access-Control-Allow-Methods")
                .contains("GET"));
        assertEquals("*", preflight.getHeaderField("Access-Control-Allow-Origin"));
        assertNull(preflight.getHeaderField("Access-Control-Allow-Credentials"));
        HttpURLConnection head = connection("/api/grids", token);
        head.setRequestMethod("HEAD");
        Response response = performSyncedRequest(() -> read(head));
        assertEquals(HttpURLConnection.HTTP_OK, response.status());
        assertEquals("", response.body());
    }

    @Test
    void loginBodyReadInPreviousListenerCannotPublishIntoRestartedServer() throws Exception {
        AE2Controller.startHTTPServer();
        CountDownLatch readingBody = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        PostExchange exchange = new PostExchange(
            "/api/auth/login",
            "{\"username\":\"admin\",\"password\":\"lifecycle-password\"}") {

            @Override
            public InputStream getRequestBody() {
                readingBody.countDown();
                try {
                    assertTrue(releaseBody.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IllegalStateException(exception);
                }
                return super.getRequestBody();
            }
        };
        ApiRouter oldRouter = authRouter();
        ExecutorService oldWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> oldRequest = oldWorker.submit(() -> {
                try {
                    oldRouter.handle(exchange);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertTrue(readingBody.await(2, TimeUnit.SECONDS));
            CoreEngine.onServerStopping();
            CoreEngine.onServerStopped();
            AE2Controller.startHTTPServer();
            releaseBody.countDown();
            oldRequest.get(3, TimeUnit.SECONDS);
            assertEquals(HttpURLConnection.HTTP_UNAVAILABLE, exchange.responseCode);
            assertEquals("SERVER_STOPPING", responseStatus(exchange));
        } finally {
            releaseBody.countDown();
            oldWorker.shutdownNow();
        }
    }

    private static ApiRouter authRouter() {
        ApiRouter router = new ApiRouter(exchange -> null, exchange -> false, ISyncedRequest::handle);
        router.register(Login.class);
        router.register(Register.class);
        return router;
    }

    @SuppressWarnings("CharsetObjectCanBeUsed") // Tests also run with Java 8.
    private static String responseStatus(PostExchange exchange) throws IOException {
        return new Gson().fromJson(exchange.responseBody.toString(StandardCharsets.UTF_8.name()), JsonObject.class)
            .get("status")
            .getAsString();
    }

    private void startApi() throws IOException {
        AE2Controller.AE2Interface = TestGridFixtures.ae();
        CoreEngine.GRID_IDENTITIES.initialize(new File(tempDirectory, "test-save"));
        AE2Controller.startHTTPServer();
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
                public void failIfPending(ApiStatus status) {}
            }));
        }
    }

    private String login(String username, String password) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        HttpURLConnection connection = jsonPost("/api/auth/login", null, body.toString());
        Response response = read(connection);
        assertEquals(HttpURLConnection.HTTP_OK, response.status());
        assertNull(
            connection.getHeaderField("Set-Cookie"),
            "API login returns a token without installing browser cookies");
        JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);
        return json.getAsJsonObject("data")
            .get("token")
            .getAsString();
    }

    private HttpURLConnection jsonPost(String path, String token, String body) throws IOException {
        HttpURLConnection connection = connection(path, token);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(encoded);
        }
        return connection;
    }

    private Response performSyncedRequest(String token) throws Exception {
        return performSyncedRequest(() -> get("/api/grids", token));
    }

    @SuppressWarnings("BusyWait") // Wait for the request to reach the tick queue, bounded by a deadline.
    private Response performSyncedRequest(Callable<Response> request) throws Exception {
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            Future<Response> response = client.submit(request);
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

    @SuppressWarnings("CharsetObjectCanBeUsed") // The Charset overload requires Java 10; tests also target Java 8.
    private static Response read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= HttpURLConnection.HTTP_BAD_REQUEST ? connection.getErrorStream()
            : connection.getInputStream();
        if (stream == null) {
            return new Response(status, "");
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new Response(status, output.toString(StandardCharsets.UTF_8.name()));
        }
    }

    private static int unusedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
