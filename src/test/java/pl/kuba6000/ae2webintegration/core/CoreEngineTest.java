package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetItems;
import pl.kuba6000.ae2webintegration.core.http.endpoint.tracking.GetResourceHistory;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

class CoreEngineTest {

    @TempDir
    File configRoot;

    @Test
    void mixedNetworkShowsUnkeyedResourcesButPersistsOnlySupportedIdentities() throws Exception {
        IAE previous = AE2Controller.AE2Interface;
        CoreEngine.init(new TestPlatform(configRoot, false));
        Config.INSTANCE.general.port = 0;
        Config.INSTANCE.general.checkForUpdates = false;
        Config.INSTANCE.resourceTracking.maxResourcesPerGrid = 2;
        try {
            CoreEngine.onServerStarted();
            ItemIdentityRequestTest.Resource iron = new ItemIdentityRequestTest.Resource("iron", 20, false);
            ItemIdentityRequestTest.Resource water = new ItemIdentityRequestTest.Resource("water", 1000, false) {

                @Override
                public @NotNull ResourceType web$getResourceType() {
                    return ResourceType.FLUID;
                }

                @Override
                public @NotNull IAEKey web$copyIdentity() {
                    return this;
                }
            };
            ItemIdentityRequestTest.Resource essentia = new ItemIdentityRequestTest.Resource("essentia", 73, false) {

                @Override
                public boolean web$supportsStableIdentity() {
                    return false;
                }

                @Override
                public @NotNull ResourceType web$getResourceType() {
                    return ResourceType.OTHER;
                }

                @Override
                public @NotNull StableKey web$getKey() {
                    throw new AssertionError("Unkeyed resource must not be encoded");
                }

                @Override
                public @NotNull IAEKey web$copyIdentity() {
                    throw new AssertionError("Unkeyed resource must not be retained for tracking");
                }
            };
            ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(771003, iron, water, essentia);
            StableKey key = TestGridFixtures.resolvedKey(grid);
            GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
            assertNotNull(data);
            data.getSettings()
                .setStorageTracked(true);
            AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
            GetItems request = new GetItems();
            assertTrue(request.init(TestGridFixtures.context(-1, "grid=" + key)));
            request.runOnServerThread(AE2Controller.AE2Interface);
            assertEquals(
                200,
                request.getResponse()
                    .httpStatus());
            JsonArray rows = new JsonParser().parse(request.getJSON())
                .getAsJsonObject()
                .getAsJsonArray("data");
            assertEquals(3, rows.size());
            JsonObject custom = null;
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                if (row.get("resourceType")
                    .getAsString()
                    .equals("OTHER")) custom = row;
            }
            assertNotNull(custom);
            assertEquals(
                73,
                custom.get("quantity")
                    .getAsLong());
            assertTrue(
                custom.get("itemKey")
                    .isJsonNull());
            StorageTrackingService service = CoreEngine.getStorageTracking();
            assertNotNull(service);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            StorageSnapshot snapshot = service.snapshot(key);
            assertNotNull(snapshot);
            while (snapshot.revision() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
                snapshot = service.snapshot(key);
                assertNotNull(snapshot);
            }
            assertTrue(snapshot.revision() > 0, "The worker must publish the mixed network observation");
            List<StorageHistoryPoint> points = service.history(key, null, 0, 30)
                .points();
            assertEquals(
                2,
                points.stream()
                    .filter(point -> point.itemKey() != null)
                    .count());
        } finally {
            CoreEngine.onServerStopped();
            AE2Controller.AE2Interface = previous;
        }
    }

    @Test
    void failedSharedObservationDoesNotFallBackToRepeatedNativeReads() {
        IAE previous = AE2Controller.AE2Interface;
        AtomicInteger copies = new AtomicInteger();
        AtomicInteger metadata = new AtomicInteger();
        CoreEngine.init(new TestPlatform(configRoot, false));
        Config.INSTANCE.general.port = 0;
        Config.INSTANCE.general.checkForUpdates = false;
        try {
            CoreEngine.onServerStarted();
            ItemIdentityRequestTest.Resource broken = new ItemIdentityRequestTest.Resource("broken", 1, false) {

                @Override
                public @NotNull IAEKey web$copyIdentity() {
                    copies.incrementAndGet();
                    throw new IllegalStateException("Native copy failed");
                }

                @Override
                public @NotNull String web$getDisplayName() {
                    metadata.incrementAndGet();
                    return super.web$getDisplayName();
                }
            };
            ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(771002, broken);
            StableKey key = TestGridFixtures.resolvedKey(grid);
            AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
            for (int attempt = 0; attempt < 3; attempt++) {
                GetItems request = new GetItems();
                assertTrue(request.init(TestGridFixtures.context(-1, "grid=" + key)));
                assertDoesNotThrow(() -> request.runOnServerThread(AE2Controller.AE2Interface));
                assertEquals(
                    500,
                    request.getResponse()
                        .httpStatus());
            }
            assertEquals(1, copies.get());
            assertEquals(0, metadata.get(), "failed observations must not trigger the legacy full-read fallback");
        } finally {
            CoreEngine.onServerStopped();
            AE2Controller.AE2Interface = previous;
        }
    }

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
            GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
            assertNotNull(data);
            data.getSettings()
                .setStorageTracked(true);
            AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
            CoreEngine.onServerTick();
            StorageTrackingService service = CoreEngine.getStorageTracking();
            assertNotNull(service);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            StorageSnapshot snapshot = service.snapshot(key);
            while ((snapshot == null || snapshot.revision() == 0) && System.nanoTime() < deadline) {
                Thread.sleep(5);
                snapshot = service.snapshot(key);
            }
            assertNotNull(snapshot);
            assertTrue(snapshot.revision() > 0);
            assertEquals(
                Long.MAX_VALUE,
                snapshot.rows()
                    .get(0)
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
            GetResourceHistory limited = new GetResourceHistory();
            AE2Controller.RequestContext limitedContext = TestGridFixtures
                .context(-1, "grid=" + key + "&itemKey=" + iron.web$getKey() + "&limit=1");
            limited
                .handle(limitedContext.withInputs(limitedContext.getPrincipal(), limitedContext.getPathParams(), null));
            JsonObject limitedResult = new JsonParser().parse(limited.getJSON())
                .getAsJsonObject()
                .getAsJsonObject("data");
            assertEquals(
                "9223372036854775807",
                limitedResult.getAsJsonObject("baseline")
                    .getAsJsonObject("resource")
                    .get("quantity")
                    .getAsString());
            data.getSettings()
                .setStorageTracked(false);
            data.getSettings()
                .setFlowTracked(true);
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
