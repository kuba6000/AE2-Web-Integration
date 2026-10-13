package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetItems;
import pl.kuba6000.ae2webintegration.core.http.endpoint.tracking.GetResourceHistory;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

class CoreEngineTest {

    @TempDir
    File configRoot;

    @Test
    void serverLifecycleOwnsStorageCaptureAndDurableHistory() throws Exception {
        IAE previous = AE2Controller.AE2Interface;
        CoreEngine.init(new TestPlatform(configRoot, false));
        Config.INSTANCE.general.port = 0;
        Config.INSTANCE.general.checkForUpdates = false;
        try {
            CoreEngine.onServerStarted();
            AtomicInteger descriptions = new AtomicInteger();
            ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource(
                "iron",
                Long.MAX_VALUE,
                false) {

                @Override
                public @NotNull String web$getDisplayName() {
                    descriptions.incrementAndGet();
                    return super.web$getDisplayName();
                }
            };
            ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(771001, iron);
            StableKey key = TestGridFixtures.resolvedKey(grid);
            CoreEngine.GRID_IDENTITIES.getPersistentData(key)
                .getSettings()
                .setStorageTracked(true);
            AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
            CoreEngine.onServerTick();
            StorageTrackingService service = CoreEngine.getStorageTracking();
            assertNotNull(service);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (service.snapshot(key)
                .revision() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(
                Long.MAX_VALUE,
                service.snapshot(key)
                    .rows()
                    .get(0)
                    .resource()
                    .quantity());
            assertFalse(
                service.history(key, null, 0, 20)
                    .points()
                    .isEmpty());
            int captured = descriptions.get();
            for (int viewer = 0; viewer < 2; viewer++) {
                GetItems request = new GetItems();
                assertTrue(request.init(TestGridFixtures.context(-1, "grid=" + key)));
                request.runOnServerThread(AE2Controller.AE2Interface);
                assertEquals(
                    Long.MAX_VALUE,
                    new JsonParser().parse(request.getJSON())
                        .getAsJsonObject()
                        .getAsJsonArray("data")
                        .get(0)
                        .getAsJsonObject()
                        .get("quantity")
                        .getAsLong());
            }
            assertEquals(captured, descriptions.get(), "viewers reuse captured native metadata");
            GetResourceHistory history = new GetResourceHistory();
            history.handle(TestGridFixtures.context(-1, "grid=" + key));
            JsonObject result = new JsonParser().parse(history.getJSON())
                .getAsJsonObject()
                .getAsJsonObject("data");
            assertTrue(
                result.get("enabled")
                    .getAsBoolean());
            boolean found = false;
            for (JsonElement element : result.getAsJsonArray("points")) {
                JsonObject point = element.getAsJsonObject();
                if (!"STOCK".equals(
                    point.get("kind")
                        .getAsString()))
                    continue;
                JsonPrimitive quantity = point.getAsJsonObject("resource")
                    .getAsJsonPrimitive("quantity");
                assertTrue(quantity.isString());
                assertEquals("9223372036854775807", quantity.getAsString());
                found = true;
            }
            assertTrue(found);
            CoreEngine.onServerStopping();
            assertNull(CoreEngine.getStorageTracking());
            assertNull(grid.web$getStorageCapture());
        } finally {
            CoreEngine.onServerStopped();
            AE2Controller.AE2Interface = previous;
        }
    }

    @Test
    void initInitializesCoreConfigDirectoryFromPlatform() {
        CoreEngine.init(new TestPlatform(configRoot, false));

        assertEquals(new File(configRoot, "ae2webintegration"), Config.getConfigDirectory());
        assertEquals(
            new File(new File(configRoot, "ae2webintegration"), "webdata.json"),
            Config.getConfigFile("webdata.json"));
        assertEquals("test-version", CoreEngine.getModVersion());
    }

    @Test
    void existingConfigStartsEvenWhenLegacyConfigCannotBeRead() {
        Config.init(configRoot);
        String password = Config.INSTANCE.general.password;

        CoreEngine.init(new TestPlatform(configRoot, true));

        assertEquals(password, Config.INSTANCE.general.password);
        assertEquals(new File(configRoot, "ae2webintegration"), Config.getConfigDirectory());
    }

    @Desugar
    private record TestPlatform(File configDirectory, boolean unreadableLegacyConfig) implements IServerPlatform {

        @Override
        public @NotNull String getModVersion() {
            return "test-version";
        }

        @Override
        public @NotNull String getLoader() {
            return "forge";
        }

        @Override
        public @NotNull String getMinecraftVersion() {
            return "1.20.1";
        }

        @Override
        public @NotNull String getIconPackCompatibilityVersion() {
            return "test-compatibility";
        }

        @Override
        public UUID getOnlinePlayerUUID(String username) {
            return null;
        }

        @Override
        public ILegacyConfigProvider getLegacyConfig() {
            if (unreadableLegacyConfig) throw new IllegalArgumentException("Malformed legacy configuration");
            return null;
        }

        @Override
        public File getConfigDirectory() {
            return configDirectory;
        }

        @Override
        public File getWorldDirectory() {
            return new File(configDirectory, "test-save");
        }

    }
}
