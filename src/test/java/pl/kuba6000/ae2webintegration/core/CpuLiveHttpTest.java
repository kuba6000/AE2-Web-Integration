package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.AEMixinCallbacks;

/** The live CPU endpoints through the real engine, router, native hooks and server tick. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CpuLiveHttpTest {

    @TempDir
    File root;

    private final CpuFixtures.Resource iron = new CpuFixtures.Resource("iron");
    private final CpuFixtures.Resource gear = new CpuFixtures.Resource("gear");
    private final CpuFixtures.Cpu cpu = new CpuFixtures.Cpu(1);
    private final CpuFixtures.Grid grid = new CpuFixtures.Grid(9301, cpu);
    private final ScheduledExecutorService game = Executors.newSingleThreadScheduledExecutor();
    private IAE previousAe;
    private String base;
    private StableKey gridKey;

    @BeforeEach
    void startEngine() throws Exception {
        previousAe = AE2Controller.AE2Interface;
        CoreEngine.init(new Platform(root));
        try (ServerSocket port = new ServerSocket(0)) {
            Config.INSTANCE.general.port = port.getLocalPort();
        }
        Config.INSTANCE.general.allowNoPasswordOnLocalhost = true;
        Config.INSTANCE.general.checkForUpdates = false;
        Config.INSTANCE.resourceTracking.activeIntervalMillis = 50;
        CoreEngine.onServerStarted();
        cpu.busy = true;
        cpu.output = gear;
        cpu.set(iron, 4, 2, 1)
            .set(gear, 0, 3, 0);
        gridKey = onGame(() -> {
            AE2Controller.AE2Interface = new CpuFixtures.Ae(grid);
            return TestGridFixtures.resolvedKey(grid);
        });
        game.scheduleAtFixedRate(CoreEngine::onServerTick, 0, 20, TimeUnit.MILLISECONDS);
        base = "http://127.0.0.1:" + Config.INSTANCE.general.port + "/api/grids/" + gridKey + "/cpus/" + cpu.key;
    }

    @AfterEach
    void stopEngine() throws Exception {
        game.shutdownNow();
        assertTrue(game.awaitTermination(3, TimeUnit.SECONDS));
        CoreEngine.onServerStopped();
        AE2Controller.AE2Interface = previousAe;
        AE2JobTracker.clearActiveJobs();
    }

    private <T> T onGame(Callable<T> task) throws Exception {
        return game.submit(task)
            .get(3, TimeUnit.SECONDS);
    }

    private void onGame(Runnable task) throws Exception {
        game.submit(task)
            .get(3, TimeUnit.SECONDS);
    }

    @Test
    void liveSnapshotCarriesAViewAndSubscriptionWhilePlainReadsStayUnchanged() throws Exception {
        JsonObject plain = json(base);
        assertTrue(
            plain.get("view")
                .isJsonNull());
        assertTrue(
            plain.get("subscription")
                .isJsonNull());
        JsonObject live = json(base + "?live=true");
        assertEquals(
            36,
            live.get("subscription")
                .getAsString()
                .length());
        assertNotNull(live.getAsJsonObject("view"));
        assertEquals(
            plain.getAsJsonObject("data"),
            live.getAsJsonObject("data"),
            "A live snapshot is the same response as a plain read");
        JsonObject first = live.getAsJsonObject("data")
            .getAsJsonArray("items")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            iron.key()
                .toString(),
            first.get("itemKey")
                .getAsString());
        assertFalse(
            first.get("crafting")
                .getAsBoolean());
        assertEquals(
            64,
            live.getAsJsonObject("data")
                .get("size")
                .getAsLong());
    }

    @Test
    void streamDeliversNativeNotificationsAndTheLeaseCanBeRenewed() throws Exception {
        JsonObject live = json(base + "?live=true");
        String token = live.get("subscription")
            .getAsString();
        assertEquals(401, status(base + "/events?subscription=" + token, "GET", true, "Bearer invalid"));
        assertEquals(400, status(base + "/events?subscription=not-a-token", "GET", false, null));
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("text/event-stream; charset=UTF-8", stream.getContentType());
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            assertEquals(403, status(base + "/events?subscription=" + token, "POST", false, null));
            assertEquals(200, status(base + "/events?subscription=" + token, "POST", true, null));
            assertEquals(400, status(base + "/events?subscription=" + UUID.randomUUID(), "POST", true, null));

            onGame(() -> {
                cpu.set(iron, 1, 2, 3);
                AEMixinCallbacks.INSTANCE.craftingStatusPostedUpdate(cpu, iron);
            });
            JsonObject delta = CpuFixtures.readEvent(input)
                .data();
            JsonObject update = delta.getAsJsonArray("updates")
                .get(0)
                .getAsJsonObject();
            assertEquals(
                1,
                update.get("active")
                    .getAsLong());
            assertEquals(
                3,
                update.get("stored")
                    .getAsLong());
        } finally {
            stream.disconnect();
        }
    }

    @Test
    void everyJobLifecycleNotificationInvalidatesTheStream() throws Exception {
        String[] hooks = { "started", "merged", "completed", "cancelled" };
        for (String hook : hooks) {
            String token = json(base + "?live=true").get("subscription")
                .getAsString();
            HttpURLConnection stream = connection(base + "/events?subscription=" + token);
            try (BufferedReader input = new BufferedReader(
                new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals(
                    "ready",
                    CpuFixtures.readEvent(input)
                        .name());
                onGame(() -> {
                    switch (hook) {
                        case "started":
                            AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, false, true);
                            break;
                        case "merged":
                            AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, true, true);
                            break;
                        case "completed":
                            AEMixinCallbacks.INSTANCE.jobCompleted(grid, cpu);
                            break;
                        default:
                            AEMixinCallbacks.INSTANCE.jobCancelled(grid, cpu);
                    }
                });
                assertEquals(
                    "invalidate",
                    CpuFixtures.readEvent(input)
                        .name(),
                    hook);
            } finally {
                stream.disconnect();
            }
        }
    }

    @Test
    void replacementReservationEndsThePreviousOneAndBadInputsAreRejected() throws Exception {
        String first = json(base + "?live=true").get("subscription")
            .getAsString();
        JsonObject replacement = json(base + "?live=true&replace=" + first);
        assertNotNull(replacement.get("subscription"));
        assertEquals(400, status(base + "/events?subscription=" + first, "POST", true, null));
        assertEquals(400, status(base + "?live=true&replace=short", "GET", false, null));
        String missing = "http://127.0.0.1:" + Config.INSTANCE.general.port
            + "/api/grids/"
            + gridKey
            + "/cpus/"
            + TestGridFixtures.key(77)
            + "?live=true";
        assertEquals(404, status(missing, "GET", false, null));
    }

    @Test
    void stoppingTheServerEndsTheViewAndReleasesTheCpuState() throws Exception {
        String token = json(base + "?live=true").get("subscription")
            .getAsString();
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            assertNotNull(CoreEngine.getCpuLive());
            game.shutdownNow();
            assertTrue(game.awaitTermination(3, TimeUnit.SECONDS));
            CoreEngine.onServerStopped();
            assertNull(CoreEngine.getCpuLive());
            assertNull(CoreEngine.getCpuRegistry());
            try {
                String line;
                do {
                    line = input.readLine(); // Drain until the server closes the stream.
                } while (line != null);
            } catch (java.io.IOException closedConnection) {
                // Stopping may abort an in-flight HTTP chunk.
            }
        } finally {
            stream.disconnect();
        }
    }

    private static HttpURLConnection connection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setReadTimeout(5000);
        return connection;
    }

    private static int status(String url, String method, boolean csrf, String authorization) throws Exception {
        HttpURLConnection connection = connection(url);
        connection.setRequestMethod(method);
        if (csrf) connection.setRequestProperty("X-AE2-Request", "true");
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
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
