package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingTestFixtures.awaitHistory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.commands.CommandProcessor;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetItems;
import pl.kuba6000.ae2webintegration.core.http.endpoint.tracking.GetResourceHistory;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

class CoreEngineTest {

    @TempDir
    File configRoot;

    @Test
    void optionalTelemetryStartupFailureDoesNotCloseWorldServices() {
        CoreEngine.init(new TestPlatform(configRoot, false, true));
        Config.INSTANCE.general.port = 0;
        Config.INSTANCE.general.checkForUpdates = false;
        try {
            assertDoesNotThrow(CoreEngine::onServerStarted);
            assertTrue(CoreEngine.GRID_IDENTITIES.isInitialized());
            assertNotNull(CoreEngine.getResourcePins());
            assertNotNull(CoreEngine.getStorageTracking());
            assertNotNull(CoreEngine.getLiveStreams());
        } finally {
            CoreEngine.onServerStopped();
        }
    }

    @Test
    void failedIdentityImportAbortsStartupWithoutPublishingServicesAndCanRetry() throws Exception {
        TestPlatform platform = new TestPlatform(configRoot, false);
        CoreEngine.init(platform);
        Config.INSTANCE.general.port = 0;
        Config.INSTANCE.general.checkForUpdates = false;
        Path source = new File(platform.getWorldDirectory(), "ae2webintegration/grid-identities.json").toPath();
        Path databaseFile = source.resolveSibling("grids.sqlite");
        byte[] invalid = "{broken".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(source.getParent());
        Files.write(source, invalid);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, CoreEngine::onServerStarted);
            IOException cause = assertInstanceOf(IOException.class, failure.getCause());
            assertInstanceOf(JsonParseException.class, cause.getCause());
            assertTrue(
                failure.getMessage()
                    .contains(databaseFile.toString()));
            assertFalse(CoreEngine.GRID_IDENTITIES.isInitialized());
            assertNull(CoreEngine.getResourcePins());
            assertNull(CoreEngine.getStorageTracking());
            assertNull(CoreEngine.getCraftingHistory());
            assertNull(CoreEngine.getLiveStreams());
            assertNull(CoreEngine.getTerminalRegistry());
            assertArrayEquals(invalid, Files.readAllBytes(source));
            try (WorldDatabase released = new WorldDatabase(databaseFile, Config.INSTANCE.resourceHistory)) {
                assertDoesNotThrow(released::open);
            }
            Files.write(source, "{}".getBytes(StandardCharsets.UTF_8));
            assertDoesNotThrow(CoreEngine::onServerStarted);
            assertTrue(CoreEngine.GRID_IDENTITIES.isInitialized());
            assertNotNull(CoreEngine.getResourcePins());
            assertNotNull(CoreEngine.getStorageTracking());
            assertNotNull(CoreEngine.getCraftingHistory());
        } finally {
            CoreEngine.onServerStopped();
        }
    }

    @Test
    void invalidDatabaseAbortsStartupPreservesTheFileAndAllowsAnotherWorld() throws Exception {
        TestPlatform platform = new TestPlatform(configRoot, false);
        CoreEngine.init(platform);
        Config.INSTANCE.general.port = 0;
        Config.INSTANCE.general.checkForUpdates = false;
        Path databaseFile = new File(platform.getWorldDirectory(), "ae2webintegration/grids.sqlite").toPath();
        byte[] original = "This existing file is not a SQLite database".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(databaseFile.getParent());
        Files.write(databaseFile, original);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, CoreEngine::onServerStarted);
            IOException cause = assertInstanceOf(IOException.class, failure.getCause());
            assertInstanceOf(SQLException.class, cause.getCause());
            assertTrue(
                failure.getMessage()
                    .contains(databaseFile.toString()));
            assertFalse(CoreEngine.GRID_IDENTITIES.isInitialized());
            assertNull(CoreEngine.getResourcePins());
            assertNull(CoreEngine.getStorageTracking());
            assertNull(CoreEngine.getCraftingHistory());
            assertNull(CoreEngine.getLiveStreams());
            assertArrayEquals(original, Files.readAllBytes(databaseFile));
            try (WorldDatabase retry = new WorldDatabase(databaseFile, Config.INSTANCE.resourceHistory)) {
                IOException rejection = assertThrows(IOException.class, retry::open);
                assertInstanceOf(SQLException.class, rejection.getCause());
            }
            CoreEngine.init(new TestPlatform(new File(configRoot, "another-world"), false));
            Config.INSTANCE.general.port = 0;
            Config.INSTANCE.general.checkForUpdates = false;
            assertDoesNotThrow(CoreEngine::onServerStarted);
            assertTrue(CoreEngine.GRID_IDENTITIES.isInitialized());
            assertNotNull(CoreEngine.getResourcePins());
            assertNotNull(CoreEngine.getStorageTracking());
            assertNotNull(CoreEngine.getCraftingHistory());
            assertArrayEquals(original, Files.readAllBytes(databaseFile));
        } finally {
            CoreEngine.onServerStopped();
        }
    }

    @Test
    void reloadPublishesValidDataBoundsAndInvalidConfigKeepsTheActivePolicy() {
        CoreEngine.init(new TestPlatform(configRoot, false));
        try (ConfigTestFixture config = new ConfigTestFixture(configRoot)) {
            config.write("general.check_for_updates", false);
            Config.reload();
            HttpServerTestFixture.startWorld(config);
            TestGridFixtures.TestGrid live = TestGridFixtures.grid(89503);
            StableKey key = CoreEngine.GRID_IDENTITIES.getKey(live);
            assertNotNull(key);
            GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
            assertNotNull(data);
            GridSettingsData settings = data.getSettings();
            settings.setName("Keep");
            settings.setStockSampleIntervalSeconds(600);
            config.write("resource_history.stock_sample_interval_seconds.max", 90);
            config.write("resource_history.stock_sample_interval_seconds.default", 60);
            assertTrue(
                CommandProcessor.reload()
                    .isSuccess());
            assertEquals(90, settings.getStockSampleIntervalSeconds());
            assertEquals("Keep", settings.getName());
            assertEquals(90, CoreEngine.GRID_IDENTITIES.getDataPolicy().stockSampleIntervalSeconds.max);
            ConfigSettings published = Config.INSTANCE;
            config.write("resource_history.stock_sample_interval_seconds.default", 91);
            assertFalse(
                CommandProcessor.reload()
                    .isSuccess());
            assertSame(published, Config.INSTANCE);
            assertEquals(90, settings.getStockSampleIntervalSeconds());
            config.write("resource_history.stock_sample_interval_seconds.default", 60);
        } finally {
            CoreEngine.onServerStopped();
        }
    }

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
            List<StorageHistoryPoint> points = awaitHistory(
                service,
                key,
                0,
                history -> history.points()
                    .stream()
                    .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.STOCK))
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
    void shutdownPersistsAnUnobservedIdentityBeforeClearingWorldState() {
        CoreEngine.init(new TestPlatform(configRoot, false));
        Config.INSTANCE.general.port = 0;
        Config.INSTANCE.general.checkForUpdates = false;
        try {
            CoreEngine.onServerStarted();
            TestGridFixtures.TestGrid grid = TestGridFixtures.grid(771101);
            StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
            assertNotNull(key);
            CoreEngine.onServerStopping();
            CoreEngine.onServerStopped();
            CoreEngine.onServerStarted();
            assertEquals(key, CoreEngine.GRID_IDENTITIES.findIdentity(grid.position()));
        } finally {
            CoreEngine.onServerStopped();
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
            assertTrue(
                awaitHistory(
                    service,
                    key,
                    0,
                    history -> history.points()
                        .stream()
                        .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.STOCK))
                    .points()
                    .stream()
                    .anyMatch(
                        point -> point.kind() == StorageHistoryPoint.Kind.STOCK && point.quantity() == Long.MAX_VALUE));
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
            limited.handle(limitedContext.withInputs(limitedContext, limitedContext.getPathParams(), null));
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
    private record TestPlatform(File configDirectory, boolean unreadableLegacyConfig, boolean telemetryFailure)
        implements IServerPlatform {

        TestPlatform(File configDirectory, boolean unreadableLegacyConfig) {
            this(configDirectory, unreadableLegacyConfig, false);
        }

        @Override
        public java.nio.file.Path getModFile() {
            return null;
        }

        @Override
        public boolean isSingleplayer() {
            if (telemetryFailure) throw new IllegalStateException("Telemetry metadata unavailable");
            return false;
        }

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
