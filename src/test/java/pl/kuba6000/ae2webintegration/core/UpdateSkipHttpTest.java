package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.config.CoreDataTestFixture;
import pl.kuba6000.ae2webintegration.core.http.endpoint.updates.SkipUpdate;
import pl.kuba6000.ae2webintegration.core.utils.ReleaseManifest.Release;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

class UpdateSkipHttpTest {

    @TempDir
    File directory;
    private ConfigTestFixture config;
    private HttpServer feedServer;
    private VersionChecker checker;
    private volatile String feed;
    private int port;
    private String token;

    @BeforeEach
    void start() throws Exception {
        CoreEngine.onServerStopped();
        CoreDataTestFixture.reset();
        config = new ConfigTestFixture(directory);
        port = ConfigTestFixture.unusedLoopbackPort();
        config.write("general.port", port);
        config.write("general.password", "test-password");
        config.write("general.allow_no_password_on_localhost", false);
        config.write("general.check_for_updates", true);
        Config.reload();
        AE2Controller.AE2Interface = TestGridFixtures.ae();
        AE2Controller.startHTTPServer();
        JsonObject login = request(
            "POST",
            "/api/auth/login",
            null,
            "{\"username\":\"admin\",\"password\":\"test-password\"}",
            200);
        token = login.getAsJsonObject("data")
            .get("token")
            .getAsString();
        feedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        feedServer.createContext("/", exchange -> {
            byte[] bytes = feed.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody()
                .write(bytes);
            exchange.close();
        });
        feedServer.start();
        useRelease("1.1.0", "stable", "forge", "1.7.10");
    }

    @AfterEach
    void stop() {
        CoreEngine.onServerStopped();
        if (feedServer != null) feedServer.stop(0);
        config.close();
        CoreDataTestFixture.reset();
    }

    @Test
    void skipPersistsAcrossReloadWhileOutdatedRemainsFactualAndPublicMetadataStaysPrivate() throws Exception {
        JsonObject publicContext = context(null);
        assertTrue(
            publicContext.get("availableUpdate")
                .isJsonNull());
        JsonObject initial = context(token);
        JsonObject update = initial.getAsJsonObject("availableUpdate");
        assertEquals(
            "1.1.0",
            update.get("version")
                .getAsString());
        assertFalse(
            update.get("skipped")
                .getAsBoolean());
        String body = "{\"tag\":\"1.1.0-forge-1.7.10\"}";
        assertTrue(
            request("POST", "/api/updates/skip", token, body, 200).get("data")
                .isJsonNull());
        request("POST", "/api/updates/skip", token, body, 200);
        CoreDataTestFixture.reset();
        CoreData.loadData();
        JsonObject reloaded = context(token);
        assertTrue(
            reloaded.get("isOutdated")
                .getAsBoolean());
        assertTrue(
            reloaded.getAsJsonObject("availableUpdate")
                .get("skipped")
                .getAsBoolean());
    }

    @Test
    void olderAndSameReleasesStaySkippedButNewerAndDifferentPlatformsResurface() throws Exception {
        skipCurrent();
        useRelease("1.0.9", "stable", "forge", "1.7.10");
        assertTrue(skipped());
        useRelease("1.2.0", "stable", "forge", "1.7.10");
        assertFalse(skipped());
        useRelease("1.1.0", "stable", "forge", "1.20.1");
        assertFalse(skipped());
        useRelease("1.1.0", "stable", "neoforge", "1.7.10");
        assertFalse(skipped());
        useRelease("1.1.0", "stable", "forge", "1.7.10");
        assertTrue(skipped());
        useRelease("1.2.0-beta.2", "prerelease", "forge", "1.7.10");
        skipCurrent();
        useRelease("1.2.0-beta.1", "prerelease", "forge", "1.7.10");
        assertTrue(skipped());
        useRelease("1.2.0-beta.3", "prerelease", "forge", "1.7.10");
        assertFalse(skipped());
        useRelease("1.2.0", "stable", "forge", "1.7.10");
        assertFalse(skipped());
        skipCurrent();
        useRelease("1.2.0-beta.3", "prerelease", "forge", "1.7.10");
        assertTrue(skipped());
    }

    @Test
    void failedSaveLeavesTheNoticeVisibleAndRetryPreservesAccounts() throws Exception {
        PlayerIdentity player = new PlayerIdentity(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), "Player");
        CoreData.setPassword(player, PasswordHelper.generateStrongPasswordHash("player-password"));
        Path blocker = Config.getConfigFile("webdata.json.tmp")
            .toPath();
        Files.createDirectory(blocker);
        Files.write(blocker.resolve("keep-directory-nonempty"), new byte[] { 1 });
        String body = "{\"tag\":\"1.1.0-forge-1.7.10\"}";
        try {
            assertEquals(
                "INTERNAL_ERROR",
                request("POST", "/api/updates/skip", token, body, 500).get("status")
                    .getAsString());
            assertFalse(skipped());
            CoreDataTestFixture.reset();
            CoreData.loadData();
            assertFalse(skipped());
            assertTrue(CoreData.verifyPassword(CoreData.getAccount("Player"), "player-password"));
        } finally {
            Files.delete(blocker.resolve("keep-directory-nonempty"));
            Files.delete(blocker);
        }
        request("POST", "/api/updates/skip", token, body, 200);
        CoreData.observePlayer(new PlayerIdentity(player.uuid, "Renamed"));
        CoreDataTestFixture.reset();
        CoreData.loadData();
        assertTrue(skipped());
        assertTrue(CoreData.verifyPassword(CoreData.getAccount("Renamed"), "player-password"));
    }

    @Test
    void skipRequiresAdminAndCsrfAndRejectsStaleOrMalformedTags() throws Exception {
        String body = "{\"tag\":\"1.1.0-forge-1.7.10\"}";
        request("POST", "/api/updates/skip", null, body, 401);
        config.set("general.public_mode", true);
        CoreData.setPassword(
            new PlayerIdentity(UUID.randomUUID(), "Player"),
            PasswordHelper.generateStrongPasswordHash("player-password"));
        String playerToken = request(
            "POST",
            "/api/auth/login",
            null,
            "{\"username\":\"Player\",\"password\":\"player-password\"}",
            200).getAsJsonObject("data")
            .get("token")
            .getAsString();
        assertTrue(
            context(playerToken).get("availableUpdate")
                .isJsonNull());
        request("POST", "/api/updates/skip", playerToken, body, 403);
        assertFalse(skipped());
        request("POST", "/api/updates/skip", "cookie:" + token, body, 403);
        for (String invalid : new String[] { "{}", "{\"tag\":null}", "{\"tag\":5}", "{\"tag\":\"\"}",
            "{\"tag\":\"a b\"}", "{\"tag\":\"" + String.join("", Collections.nCopies(257, "a")) + "\"}" }) {
            request("POST", "/api/updates/skip", token, invalid, 400);
        }
        assertEquals(
            "UPDATE_CHANGED",
            request("POST", "/api/updates/skip", token, "{\"tag\":\"2.0.0-forge-1.7.10\"}", 409).get("status")
                .getAsString());
        config.set("general.check_for_updates", false);
        request("POST", "/api/updates/skip", token, body, 409);
        assertTrue(
            context(token).get("availableUpdate")
                .isJsonNull());
        assertFalse(
            context(token).get("isOutdated")
                .getAsBoolean());
        config.set("general.check_for_updates", true);
        request("POST", "/api/updates/skip", "marked-cookie:" + token, body, 200);
        assertTrue(skipped());
    }

    @Test
    void malformedSavedSkipDoesNotBreakContextOrDiscardLoadedAccounts() throws Exception {
        CoreData.setPassword(
            new PlayerIdentity(UUID.randomUUID(), "Player"),
            PasswordHelper.generateStrongPasswordHash("password"));
        for (String invalid : new String[] {
            "{\"minecraftVersion\":\"1.7.10\",\"loader\":\"forge\",\"version\":\"garbage\",\"channel\":\"STABLE\",\"tag\":\"bad-tag\"}",
            "{}" }) {
            Files.write(
                Config.getConfigFile("webdata.json")
                    .toPath(),
                ("{\"skippedUpdate\":" + invalid + "}").getBytes(StandardCharsets.UTF_8));
            CoreData.loadData();
            assertFalse(skipped());
            assertNotNull(CoreData.getAccount("Player"));
        }
    }

    @Test
    void requestCapturedBeforeHttpRestartCannotSkipARelease() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("tag", "1.1.0-forge-1.7.10");
        SkipUpdate request = new SkipUpdate();
        assertTrue(
            request.init(new AE2Controller.RequestContext(null, WebPrincipal.admin(), Collections.emptyMap(), body)));
        AE2Controller.stopHTTPServer();
        AE2Controller.startHTTPServer();
        request.handle();
        assertEquals(
            "SERVER_STOPPING",
            new Gson().fromJson(request.getJSON(), JsonObject.class)
                .get("status")
                .getAsString());
        Release release = checker.getAvailableUpdate();
        assertNotNull(release);
        assertFalse(CoreData.isUpdateSkipped("1.7.10", "forge", release));
    }

    private boolean skipped() throws Exception {
        return context(token).getAsJsonObject("availableUpdate")
            .get("skipped")
            .getAsBoolean();
    }

    private void skipCurrent() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty(
            "tag",
            context(token).getAsJsonObject("availableUpdate")
                .get("tag")
                .getAsString());
        request("POST", "/api/updates/skip", token, body.toString(), 200);
    }

    private void useRelease(String version, String channel, String loader, String minecraft) throws Exception {
        if (checker != null) checker.close();
        String tag = version + "-" + loader + "-" + minecraft;
        JsonObject release = new JsonObject();
        release.addProperty("newest", version);
        release.addProperty("timestamp", 1786902605);
        release.addProperty("github_release_tag", tag);
        release.addProperty("github_release_url", "https://github.com/example/mod/releases/tag/" + tag);
        release.addProperty(
            "github_release_download_url",
            "https://github.com/example/mod/releases/download/" + tag + "/mod.jar");
        JsonObject releases = new JsonObject();
        releases.add("stable", null);
        releases.add("prerelease", null);
        releases.add(channel, release);
        JsonObject root = new JsonObject();
        root.addProperty("version", minecraft);
        root.add("releases", releases);
        feed = root.toString();
        checker = new VersionChecker(
            new URL(
                "http://127.0.0.1:" + feedServer.getAddress()
                    .getPort() + "/"),
            "1.0.0-alpha.1-" + loader + "-" + minecraft,
            loader,
            minecraft);
        Field field = CoreEngine.class.getDeclaredField("versionChecker");
        field.setAccessible(true);
        field.set(null, checker);
        checker.checkForUpdates()
            .get(5, TimeUnit.SECONDS);
    }

    private JsonObject context(String credentials) throws Exception {
        return request("GET", "/api/context", credentials, null, 200).getAsJsonObject("data");
    }

    private JsonObject request(String method, String path, String credentials, String body, int expected)
        throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod(method);
        if (credentials != null) {
            if (credentials.startsWith("cookie:") || credentials.startsWith("marked-cookie:")) {
                connection.setRequestProperty(
                    "Cookie",
                    "authenticationToken=" + credentials.substring(credentials.indexOf(':') + 1));
                if (credentials.startsWith("marked-cookie:")) connection.setRequestProperty("X-AE2-Request", "true");
            } else connection.setRequestProperty("Authorization", "Bearer " + credentials);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        assertEquals(expected, connection.getResponseCode());
        try (InputStream input = expected >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            return new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
        } finally {
            connection.disconnect();
        }
    }
}
