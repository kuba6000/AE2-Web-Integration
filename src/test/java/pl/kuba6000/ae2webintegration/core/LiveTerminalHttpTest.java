package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

class LiveTerminalHttpTest {

    @TempDir
    File root;

    @Test
    void liveBootstrapCanReplaceItsPreviousHalfClosedSubscriptionAtCapacity() throws Exception {
        IAE previous = AE2Controller.AE2Interface;
        ScheduledExecutorService game = Executors.newSingleThreadScheduledExecutor();
        List<Socket> streams = new ArrayList<>();
        CoreEngine.init(new Platform(root));
        try (ServerSocket port = new ServerSocket(0)) {
            Config.INSTANCE.general.port = port.getLocalPort();
        }
        Config.INSTANCE.general.allowNoPasswordOnLocalhost = true;
        Config.INSTANCE.general.checkForUpdates = false;
        Config.INSTANCE.resourceTracking.activeIntervalMillis = 50;
        try {
            CoreEngine.onServerStarted();
            ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(
                443325,
                new ItemIdentityRequestTest.Resource("iron", 10, false));
            StableKey key = game.submit(() -> {
                AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
                return TestGridFixtures.resolvedKey(grid);
            })
                .get(3, TimeUnit.SECONDS);
            var ticks = game.scheduleAtFixedRate(CoreEngine::onServerTick, 0, 20, TimeUnit.MILLISECONDS);
            String path = "/api/grids/" + key + "/items";
            String base = "http://127.0.0.1:" + Config.INSTANCE.general.port + path;
            String previousSubscription = null;
            for (int client = 0; client < 6; client++) {
                JsonObject snapshot = json(base + "?live=true");
                String subscription = snapshot.get("subscription")
                    .getAsString();
                if (client == 0) {
                    previousSubscription = subscription;
                    assertEquals(400, renew(base, subscription, true));
                }
                Socket socket = new Socket("127.0.0.1", Config.INSTANCE.general.port);
                streams.add(socket);
                socket.setSoTimeout(3000);
                socket.getOutputStream()
                    .write(
                        ("GET " + path
                            + "/events?subscription="
                            + subscription
                            + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                BufferedReader input = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                do {
                    line = input.readLine();
                    assertNotNull(line);
                } while (!line.equals("event: ready"));
                socket.shutdownOutput();
            }
            ticks.cancel(false);
            assertEquals(403, renew(base, previousSubscription, false));
            assertEquals(
                400,
                renew(
                    base,
                    UUID.randomUUID()
                        .toString(),
                    true));
            assertEquals(200, renew(base, previousSubscription, true));
            game.scheduleAtFixedRate(CoreEngine::onServerTick, 0, 20, TimeUnit.MILLISECONDS);
            HttpURLConnection full = connection(base + "?live=true");
            try {
                assertEquals(503, full.getResponseCode());
            } finally {
                full.disconnect();
            }
            JsonObject replacement = json(base + "?live=true&replace=" + previousSubscription);
            assertNotNull(replacement.get("subscription"));
            assertEquals(400, renew(base, previousSubscription, true));
            assertEquals(
                10,
                replacement.getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject()
                    .get("quantity")
                    .getAsLong());
        } finally {
            for (Socket socket : streams) socket.close();
            game.shutdownNow();
            game.awaitTermination(3, TimeUnit.SECONDS);
            CoreEngine.onServerStopped();
            AE2Controller.AE2Interface = previous;
        }
    }

    private static int renew(String base, String token, boolean csrf) throws Exception {
        HttpURLConnection connection = connection(base + "/events?subscription=" + token);
        connection.setRequestMethod("POST");
        if (csrf) connection.setRequestProperty("X-AE2-Request", "true");
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void liveSnapshotAndRegisteredStreamShareNativeCaptureAndEnforceAuthentication() throws Exception {
        IAE previous = AE2Controller.AE2Interface;
        ScheduledExecutorService game = Executors.newSingleThreadScheduledExecutor();
        CoreEngine.init(new Platform(root));
        try (ServerSocket port = new ServerSocket(0)) {
            Config.INSTANCE.general.port = port.getLocalPort();
        }
        Config.INSTANCE.general.allowNoPasswordOnLocalhost = true;
        Config.INSTANCE.general.checkForUpdates = false;
        Config.INSTANCE.resourceTracking.activeIntervalMillis = 50;
        try {
            CoreEngine.onServerStarted();
            ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("iron", 10, false);
            ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(443324, iron);
            StableKey key = game.submit(() -> {
                AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
                StableKey gridKey = TestGridFixtures.resolvedKey(grid);
                var data = CoreEngine.GRID_IDENTITIES.getPersistentData(gridKey);
                assertNotNull(data);
                data.getSettings()
                    .setFlowTracked(true);
                return gridKey;
            })
                .get(3, TimeUnit.SECONDS);
            java.util.concurrent.ScheduledFuture<?> ticks = game
                .scheduleAtFixedRate(CoreEngine::onServerTick, 0, 20, TimeUnit.MILLISECONDS);
            String base = "http://127.0.0.1:" + Config.INSTANCE.general.port + "/api/grids/" + key + "/items";
            for (int i = 0; i < 6; i++) {
                HttpURLConnection head = connection(base + "?live=true");
                head.setRequestMethod("HEAD");
                assertEquals(200, head.getResponseCode());
                head.disconnect();
            }
            JsonObject snapshot = json(base + "?live=true");
            JsonObject recent = snapshot.get("recent")
                .isJsonNull() ? null : snapshot.getAsJsonObject("recent");
            if (recent != null) assertRecentFlow(recent, iron.web$getKey());
            JsonObject view = snapshot.getAsJsonObject("view");
            assertNotNull(view);
            assertTrue(
                view.get("generation")
                    .getAsJsonPrimitive()
                    .isString());
            assertTrue(
                view.get("revision")
                    .getAsJsonPrimitive()
                    .isString());
            assertEquals(
                10,
                snapshot.getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject()
                    .get("quantity")
                    .getAsLong());
            ticks.cancel(false);
            String token = snapshot.get("subscription")
                .getAsString();
            HttpURLConnection denied = connection(base + "/events?subscription=" + token);
            denied.setRequestProperty("Authorization", "Bearer invalid");
            assertEquals(401, denied.getResponseCode());
            denied.disconnect();
            HttpURLConnection invalid = connection(base + "/events?subscription=not-a-token");
            assertEquals(400, invalid.getResponseCode());
            invalid.disconnect();
            HttpURLConnection stream = connection(base + "/events?subscription=" + token);
            try (BufferedReader input = new BufferedReader(
                new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("text/event-stream; charset=UTF-8", stream.getContentType());
                game.scheduleAtFixedRate(CoreEngine::onServerTick, 0, 20, TimeUnit.MILLISECONDS);
                game.submit(() -> {
                    grid.rows.set(0, new ItemIdentityRequestTest.Resource("iron", 25, false));
                    assertNotNull(grid.web$getStorageCapture());
                    grid.web$getStorageCapture()
                        .markChanged(iron);
                })
                    .get(3, TimeUnit.SECONDS);
                boolean updated = false;
                boolean receivedRecent = recent != null;
                for (int remaining = 30; remaining > 0 && (!updated || !receivedRecent);) {
                    String line = input.readLine();
                    assertNotNull(line);
                    if (!line.startsWith("data: ")) continue;
                    remaining--;
                    JsonObject payload = new JsonParser().parse(line.substring(6))
                        .getAsJsonObject();
                    if (payload.has("recent") && !payload.get("recent")
                        .isJsonNull()) {
                        assertRecentFlow(payload.getAsJsonObject("recent"), iron.web$getKey());
                        receivedRecent = true;
                    }
                    if (!payload.has("updates")) continue;
                    for (JsonElement row : payload.getAsJsonArray("updates")) {
                        if (row.getAsJsonObject()
                            .get("quantity")
                            .getAsLong() == 25) updated = true;
                    }
                }
                assertTrue(updated, "The native change must reach the authorized terminal stream");
                assertTrue(receivedRecent, "Recent data may arrive after bootstrap through its existing subscription");
            } finally {
                stream.disconnect();
            }
            JsonObject legacy = json(base);
            assertEquals(
                25,
                legacy.getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject()
                    .get("quantity")
                    .getAsLong());
            assertTrue(
                !legacy.has("view") || legacy.get("view")
                    .isJsonNull());
        } finally {
            game.shutdownNow();
            game.awaitTermination(3, TimeUnit.SECONDS);
            CoreEngine.onServerStopped();
            AE2Controller.AE2Interface = previous;
        }
    }

    private static void assertRecentFlow(JsonObject recent, StableKey iron) {
        JsonObject amounts = recent.getAsJsonObject("resources");
        if (amounts.has(iron.toString())) assertEquals(
            "0",
            amounts.getAsJsonObject(iron.toString())
                .get("input")
                .getAsString());
        assertTrue(
            recent.get("stockSince")
                .isJsonNull());
    }

    private static HttpURLConnection connection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setReadTimeout(5000);
        return connection;
    }

    private static JsonObject json(String url) throws Exception {
        HttpURLConnection connection = connection(url);
        try (InputStreamReader input = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(input)
                .getAsJsonObject();
        } finally {
            connection.disconnect();
        }
    }

    @Desugar
    private record Platform(File root) implements IServerPlatform {

        @Override
        public UUID getOnlinePlayerUUID(String username) {
            return null;
        }

        @Override
        public File getConfigDirectory() {
            return root;
        }

        @Override
        public ILegacyConfigProvider getLegacyConfig() {
            return null;
        }

        @Override
        public File getWorldDirectory() {
            return new File(root, "save");
        }

        @Override
        public @NotNull String getModVersion() {
            return "test";
        }

        @Override
        public @NotNull String getLoader() {
            return "forge";
        }

        @Override
        public @NotNull String getMinecraftVersion() {
            return "1.7.10";
        }

        @Override
        public @NotNull String getIconPackCompatibilityVersion() {
            return "test";
        }
    }
}
