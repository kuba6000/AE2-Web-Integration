package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.notification.NotificationManager;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingNotificationTest extends GridTestScope {

    @ParameterizedTest
    @CsvSource({ "true, false", "true, true", "false, false", "false, true" })
    void bothDestinationsUseTheGridLabelAtCompletion(boolean named, boolean cancelled) throws Exception {
        BlockingQueue<JsonObject> discord = new LinkedBlockingQueue<>();
        BlockingQueue<JsonObject> ntfy = new LinkedBlockingQueue<>();
        CountDownLatch workerBlocked = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/discord", exchange -> {
            JsonObject payload = new JsonParser()
                .parse(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            JsonObject embed = payload.getAsJsonArray("embeds")
                .get(0)
                .getAsJsonObject();
            if (!embed.get("description")
                .getAsString()
                .contains("Display notification-output")) {
                workerBlocked.countDown();
                try {
                    assertTrue(releaseWorker.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread()
                        .interrupt();
                    throw new AssertionError(failure);
                }
            } else {
                assertTrue(discord.offer(embed));
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/ntfy", exchange -> {
            JsonObject payload = new JsonParser()
                .parse(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            if (payload.get("message")
                .getAsString()
                .contains("Display notification-output")) {
                assertTrue(ntfy.offer(payload));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (ConfigTestFixture ignored = new ConfigTestFixture()) {
            Config.INSTANCE.general.publicMode = false;
            Config.INSTANCE.discord.generateImage = false;
            String endpoint = "http://127.0.0.1:" + server.getAddress()
                .getPort();
            Config.INSTANCE.discord.webhook = endpoint + "/discord";
            Config.INSTANCE.ntfy.host = endpoint + "/ntfy";
            Config.INSTANCE.ntfy.topic = "crafting-test";
            NotificationManager.init();
            try {
                assertTrue(workerBlocked.await(5, TimeUnit.SECONDS));
                CpuFixtures.Cpu cpu = new CpuFixtures.Cpu(900_107L);
                cpu.output = new CpuFixtures.Resource("notification-output");
                cpu.outputAmount = 5;
                CpuFixtures.Grid grid = new CpuFixtures.Grid(900_106L, cpu);
                TestGridFixtures.track(grid);
                StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
                assertNotNull(key);
                GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
                assertNotNull(data);
                GridSettingsData settings = data.getSettings();
                settings.setName("Name when the job started");
                AE2JobTracker.addJob(cpu, grid, false, null);
                String name = named ? "Factory \"A\" – Łódź" : "";
                settings.setName(name);

                if (cancelled) AE2JobTracker.cancelCrafting(grid, cpu);
                else AE2JobTracker.completeCrafting(grid, cpu);

                // The queued notification describes completion, even if delivery follows a later rename.
                settings.setName("Renamed after completion");
                releaseWorker.countDown();
                String label = named ? name : key.toString();
                assertPayload(discord.poll(5, TimeUnit.SECONDS), "description", label, cancelled);
                assertPayload(ntfy.poll(5, TimeUnit.SECONDS), "message", label, cancelled);
            } finally {
                releaseWorker.countDown();
                NotificationManager.shutdown();
                AE2JobTracker.clearActiveJobs();
            }
        } finally {
            server.stop(0);
        }
    }

    private static void assertPayload(JsonObject payload, String descriptionField, String label, boolean cancelled) {
        assertNotNull(payload, "The crafting notification must reach its local destination");
        String title = payload.get("title")
            .getAsString();
        assertTrue(title.contains(label), title);
        assertFalse(title.contains("Renamed after completion"));
        String description = payload.get(descriptionField)
            .getAsString();
        assertTrue(description.contains("Display notification-output x5"), description);
        assertTrue(description.contains(cancelled ? "cancelled" : "completed"), description);
    }
}
