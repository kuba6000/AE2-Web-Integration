package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.server.PostServerSettings;

class ServerSettingsHttpTest {

    @TempDir
    File directory;
    private ConfigTestFixture config;
    private int port;
    private String token;

    @BeforeEach
    void start() throws Exception {
        CoreEngine.onServerStopped();
        config = new ConfigTestFixture(directory);
        port = ConfigTestFixture.unusedLoopbackPort();
        config.write("general.port", port);
        config.write("general.password", "test-password");
        config.write("general.allow_no_password_on_localhost", false);
        config.write("general.check_for_updates", false);
        Config.reload();
        AE2Controller.AE2Interface = TestGridFixtures.ae();
        AE2Controller.startHTTPServer();
        token = request("POST", "/api/auth/login", null, "{\"username\":\"admin\",\"password\":\"test-password\"}", 200)
            .getAsJsonObject("data")
            .get("token")
            .getAsString();
    }

    @AfterEach
    void stop() {
        CoreEngine.onServerStopped();
        config.close();
    }

    @Test
    void readsEditableSettingsAndFileOnlyMetadataWithoutAdminPassword() throws Exception {
        JsonObject data = request("GET", "/api/server/settings", token, null, 200).getAsJsonObject("data");
        assertFalse(
            data.get("revision")
                .getAsString()
                .isEmpty());
        JsonObject settings = data.getAsJsonObject("settings");
        assertFalse(
            settings.getAsJsonObject("general")
                .has("password"));
        assertFalse(
            settings.getAsJsonObject("general")
                .has("port"));
        assertTrue(
            settings.getAsJsonObject("ntfy")
                .has("password"));
        assertTrue(settings.has("resourceHistory"));
        assertFalse(
            settings.getAsJsonObject("general")
                .has("publicMode"));
        assertFalse(
            settings.getAsJsonObject("general")
                .has("allowNoPasswordOnLocalhost"));
        JsonObject readOnly = data.getAsJsonObject("readOnly");
        assertEquals(
            port,
            readOnly.get("port")
                .getAsInt());
        assertTrue(
            readOnly.get("publicMode")
                .getAsBoolean());
        assertFalse(
            readOnly.get("allowNoPasswordOnLocalhost")
                .getAsBoolean());
        assertTrue(
            readOnly.get("adminPasswordConfigured")
                .getAsBoolean());
        assertFalse(
            data.toString()
                .contains("test-password"));
        request("POST", "/api/server/settings", token, data.toString(), 400);
        config.write("general.password", "");
        assertFalse(
            request("GET", "/api/server/settings", token, null, 200).getAsJsonObject("data")
                .getAsJsonObject("readOnly")
                .get("adminPasswordConfigured")
                .getAsBoolean());
    }

    @Test
    void savesAndAppliesWithoutRestartPreservingFileOnlyValuesAndUnknownKeys() throws Exception {
        config.write("general.password", "file-edited-password");
        config.write("general.port", port + 1);
        config.write("general.public_mode", false);
        config.write("general.allow_no_password_on_localhost", true);
        config.write("extension.custom", "keep-me");
        Path file = Config.getConfigFile("config.toml")
            .toPath();
        String original = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Files.write(file, ("# administrator comment\n" + original).getBytes(StandardCharsets.UTF_8));
        JsonObject body = request("GET", "/api/server/settings", token, null, 200).getAsJsonObject("data");
        JsonObject readOnly = body.getAsJsonObject("readOnly");
        assertEquals(
            port + 1,
            readOnly.get("port")
                .getAsInt());
        assertFalse(
            readOnly.get("publicMode")
                .getAsBoolean());
        assertTrue(
            readOnly.get("allowNoPasswordOnLocalhost")
                .getAsBoolean());
        body.remove("readOnly");
        String previousRevision = body.get("revision")
            .getAsString();
        body.getAsJsonObject("settings")
            .getAsJsonObject("notifications")
            .addProperty("minimumCraftingAmount", 123);
        JsonObject saved = request("POST", "/api/server/settings", token, body.toString(), 200).getAsJsonObject("data");
        assertNotEquals(
            previousRevision,
            saved.get("revision")
                .getAsString());
        assertEquals(123, Config.INSTANCE.notifications.minimumCraftingAmount);
        assertEquals("test-password", Config.INSTANCE.general.password);
        assertEquals(port, Config.INSTANCE.general.port);
        assertTrue(Config.INSTANCE.general.publicMode);
        assertFalse(Config.INSTANCE.general.allowNoPasswordOnLocalhost);
        String contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(contents.contains("administrator comment"));
        assertTrue(contents.contains("keep-me"));
        assertTrue(contents.contains("file-edited-password"));
        request("GET", "/api/server/settings", token, null, 200);
        Config.reload();
        assertEquals("file-edited-password", Config.INSTANCE.general.password);
        assertEquals(port + 1, Config.INSTANCE.general.port);
        assertFalse(Config.INSTANCE.general.publicMode);
        assertTrue(Config.INSTANCE.general.allowNoPasswordOnLocalhost);
        assertEquals(123, Config.INSTANCE.notifications.minimumCraftingAmount);
    }

    @Test
    void staleFileOrBrowserRevisionCannotOverwriteNewerSettings() throws Exception {
        JsonObject old = readSettings();
        JsonObject edit = new Gson().fromJson(old.toString(), JsonObject.class);
        edit.getAsJsonObject("settings")
            .getAsJsonObject("notifications")
            .addProperty("minimumCraftingAmount", 55);
        request("POST", "/api/server/settings", token, edit.toString(), 200);
        assertEquals(
            "CONFIG_CHANGED",
            request("POST", "/api/server/settings", token, old.toString(), 409).get("status")
                .getAsString());
        JsonObject fresh = readSettings();
        config.write("general.password", "external-change");
        assertEquals(
            "CONFIG_CHANGED",
            request("POST", "/api/server/settings", token, fresh.toString(), 409).get("status")
                .getAsString());
        assertEquals(55, Config.INSTANCE.notifications.minimumCraftingAmount);
        assertEquals("test-password", Config.INSTANCE.general.password);
    }

    @Test
    void invalidAndForbiddenFieldsNeverChangeFileOrPublishedSettings() throws Exception {
        JsonObject original = readSettings();
        byte[] contents = Files.readAllBytes(
            Config.getConfigFile("config.toml")
                .toPath());
        Object active = Config.INSTANCE;
        for (String forbidden : new String[] { "password", "port", "unknown" }) {
            JsonObject edit = new Gson().fromJson(original.toString(), JsonObject.class);
            edit.getAsJsonObject("settings")
                .getAsJsonObject("general")
                .addProperty(forbidden, "must-not-write");
            request("POST", "/api/server/settings", token, edit.toString(), 400);
        }
        JsonObject edit = new Gson().fromJson(original.toString(), JsonObject.class);
        edit.getAsJsonObject("settings")
            .getAsJsonObject("resourceHistory")
            .getAsJsonObject("historyRetentionDays")
            .addProperty("min", 999);
        request("POST", "/api/server/settings", token, edit.toString(), 400);
        edit = new Gson().fromJson(original.toString(), JsonObject.class);
        edit.getAsJsonObject("settings")
            .getAsJsonObject("resourceTracking")
            .addProperty("activeIntervalMillis", 0);
        request("POST", "/api/server/settings", token, edit.toString(), 400);
        edit = new Gson().fromJson(original.toString(), JsonObject.class);
        edit.getAsJsonObject("settings")
            .getAsJsonObject("general")
            .addProperty("publicMode", "true");
        request("POST", "/api/server/settings", token, edit.toString(), 400);
        edit = new Gson().fromJson(original.toString(), JsonObject.class);
        edit.getAsJsonObject("settings")
            .remove("ntfy");
        request("POST", "/api/server/settings", token, edit.toString(), 400);
        edit = new Gson().fromJson(original.toString(), JsonObject.class);
        edit.getAsJsonObject("settings")
            .getAsJsonObject("ntfy")
            .addProperty("password", "bad\u0000secret");
        request("POST", "/api/server/settings", token, edit.toString(), 400);
        assertArrayEquals(
            contents,
            Files.readAllBytes(
                Config.getConfigFile("config.toml")
                    .toPath()));
        assertSame(active, Config.INSTANCE);
    }

    @Test
    void failedAtomicWriteLeavesDiskAndRuntimeUntouched() throws Exception {
        JsonObject edit = readSettings();
        edit.getAsJsonObject("settings")
            .getAsJsonObject("ntfy")
            .addProperty("password", "ntfy-secret");
        byte[] contents = Files.readAllBytes(
            Config.getConfigFile("config.toml")
                .toPath());
        Object active = Config.INSTANCE;
        Path blocker = Config.getConfigFile("config.toml.tmp")
            .toPath();
        Files.createDirectory(blocker);
        Files.write(blocker.resolve("keep"), new byte[] { 1 });
        try {
            request("POST", "/api/server/settings", token, edit.toString(), 500);
        } finally {
            Files.delete(blocker.resolve("keep"));
            Files.delete(blocker);
        }
        assertSame(active, Config.INSTANCE);
        assertArrayEquals(
            contents,
            Files.readAllBytes(
                Config.getConfigFile("config.toml")
                    .toPath()));
        request("POST", "/api/server/settings", token, edit.toString(), 200);
        assertEquals("ntfy-secret", Config.INSTANCE.ntfy.password);
    }

    @Test
    void anonymousAndPlayerCannotReadOrSaveButTrustedLocalhostCan() throws Exception {
        JsonObject edit = readSettings();
        request("GET", "/api/server/settings", null, null, 401);
        request("POST", "/api/server/settings", null, edit.toString(), 401);
        CoreData.setPassword(
            new PlayerIdentity(UUID.randomUUID(), "SettingsPlayer"),
            PasswordHelper.generateStrongPasswordHash("player-password"));
        String player = request(
            "POST",
            "/api/auth/login",
            null,
            "{\"username\":\"SettingsPlayer\",\"password\":\"player-password\"}",
            200).getAsJsonObject("data")
            .get("token")
            .getAsString();
        request("GET", "/api/server/settings", player, null, 403);
        request("POST", "/api/server/settings", player, edit.toString(), 403);
        request("POST", "/api/server/settings", "cookie:" + token, edit.toString(), 403);
        config.set("general.allow_no_password_on_localhost", true);
        JsonObject local = request("GET", "/api/server/settings", null, null, 200).getAsJsonObject("data");
        local.remove("readOnly");
        request("POST", "/api/server/settings", null, local.toString(), 403);
        request("POST", "/api/server/settings", "local", local.toString(), 200);
    }

    @Test
    void obsoleteHttpGenerationCannotSave() throws Exception {
        JsonObject edit = readSettings();
        edit.getAsJsonObject("settings")
            .getAsJsonObject("notifications")
            .addProperty("minimumCraftingAmount", 777);
        PostServerSettings pending = new PostServerSettings();
        assertTrue(
            pending.init(new AE2Controller.RequestContext(null, WebPrincipal.admin(), Collections.emptyMap(), edit)));
        AE2Controller.stopHTTPServer();
        AE2Controller.startHTTPServer();
        pending.handle();
        assertEquals(
            "SERVER_STOPPING",
            new Gson().fromJson(pending.getJSON(), JsonObject.class)
                .get("status")
                .getAsString());
        assertNotEquals(777, Config.INSTANCE.notifications.minimumCraftingAmount);
    }

    @Test
    void fileOnlyAccessFlagsCannotBeChangedByWebClients() throws Exception {
        for (String field : new String[] { "publicMode", "allowNoPasswordOnLocalhost" }) {
            JsonObject edit = readSettings();
            edit.getAsJsonObject("settings")
                .getAsJsonObject("general")
                .addProperty(field, true);
            request("POST", "/api/server/settings", token, edit.toString(), 400);
        }
    }

    private JsonObject readSettings() throws Exception {
        JsonObject input = request("GET", "/api/server/settings", token, null, 200).getAsJsonObject("data");
        input.remove("readOnly");
        return input;
    }

    private JsonObject request(String method, String path, String credentials, String body, int expected)
        throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod(method);
        if ("local".equals(credentials)) connection.setRequestProperty("X-AE2-Request", "true");
        else if (credentials != null && credentials.startsWith("cookie:"))
            connection.setRequestProperty("Cookie", "authenticationToken=" + credentials.substring(7));
        else if (credentials != null) connection.setRequestProperty("Authorization", "Bearer " + credentials);
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
