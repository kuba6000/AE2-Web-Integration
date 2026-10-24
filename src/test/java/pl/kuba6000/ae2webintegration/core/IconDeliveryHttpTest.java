package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingTestFixtures.awaitHistory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.ItemIdentityRequestTest.Grid;
import pl.kuba6000.ae2webintegration.core.ItemIdentityRequestTest.Resource;
import pl.kuba6000.ae2webintegration.core.TestGridFixtures.TestGrid;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.Discord.CraftingStyle;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.endpoint.pins.DeleteResourcePin;
import pl.kuba6000.ae2webintegration.core.http.endpoint.pins.GetResourcePins;
import pl.kuba6000.ae2webintegration.core.http.endpoint.pins.PatchResourcePin;
import pl.kuba6000.ae2webintegration.core.http.endpoint.pins.PostResourcePin;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.notification.destination.discord.DiscordDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.HomeConfig;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Pin;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Placement;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.crafting.CraftingHistoryService;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

/** Public HTTP and server lifecycle contract; generated packs use the public archive writer. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class IconDeliveryHttpTest {

    @TempDir
    Path directory;
    private ConfigTestFixture config;
    private IServerPlatform previousPlatform;
    private int port;
    private String packId;
    private String digest;
    private byte[] png;

    @BeforeEach
    void setUp() throws Exception {
        CoreEngine.onServerStopped();
        previousPlatform = AE2Controller.serverPlatform;
        config = new ConfigTestFixture(directory.toFile());
        config.write("general.allow_no_password_on_localhost", true);
        config.write("general.check_for_updates", false);
        Config.reload();
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public java.nio.file.Path getModFile() {
                return null;
            }

            @Override
            public boolean isSingleplayer() {
                return false;
            }

            @Override
            public @NotNull String getModVersion() {
                return "test-version";
            }

            public UUID getOnlinePlayerUUID(String username) {
                return null;
            }

            public File getConfigDirectory() {
                return directory.toFile();
            }

            public ILegacyConfigProvider getLegacyConfig() {
                return null;
            }

            public File getWorldDirectory() {
                return directory.resolve("world")
                    .toFile();
            }

            public @NotNull String getMinecraftVersion() {
                return "1.7.10";
            }

            public @NotNull String getLoader() {
                return "forge";
            }

            public @NotNull String getIconPackCompatibilityVersion() {
                return "test-identity";
            }
        };
        AE2Controller.AE2Interface = TestGridFixtures.ae();
    }

    @AfterEach
    void tearDown() {
        CoreEngine.onServerStopped();
        AE2Controller.serverPlatform = previousPlatform;
        config.close();
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void discordThumbnailUsesInstalledIconsOrFallsBackToText(int scenario) throws Exception {
        StableKey exact = StableKey.random();
        StableKey base = StableKey.random();
        if (scenario != 3) {
            IconPack.Metadata metadata = new IconPack.Metadata(
                "1.7.10",
                "forge",
                "test-identity",
                "test",
                "today",
                Collections.emptyMap(),
                Collections.emptyList());
            try (IconPackWriter writer = new IconPackWriter(directory, metadata, 512)) {
                int[] pixels = new int[4096];
                Arrays.fill(pixels, 0xff1234ab);
                writer.add(exact, pixels);
                Arrays.fill(pixels, 0xffab3412);
                writer.add(base, pixels);
                Files.move(writer.finish(), directory.resolve("ae2webintegration/icons.ae2wi-icons"));
            }
        }
        port = HttpServerTestFixture.startWorld(config);
        if (scenario == 4) CoreEngine.getIconPack()
            .close();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/", exchange -> {
            contentType.set(
                exchange.getRequestHeaders()
                    .getFirst("Content-Type"));
            body.set(IOUtils.toByteArray(exchange.getRequestBody()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        endpoint.start();
        try {
            Config.INSTANCE.discord.webhook = "http://127.0.0.1:" + endpoint.getAddress()
                .getPort() + "/";
            Config.INSTANCE.discord.roleId = "12345";
            Config.INSTANCE.discord.craftingStyle = CraftingStyle.SMALL_ICON;
            CraftingMessage message = new CraftingMessage(
                "Factory",
                "CPU",
                new ResourceSnapshot(
                    new ResourceDescriptor(
                        "minecraft",
                        "rail",
                        0,
                        0,
                        "Rail",
                        ResourceType.ITEM,
                        scenario == 1 || scenario == 2 ? StableKey.random() : exact,
                        scenario == 2 ? null : base),
                    64),
                "1m",
                false,
                Arrays.asList(CraftingRequester.automation(), CraftingRequester.web()));
            URL classes = DiscordDestination.class.getProtectionDomain()
                .getCodeSource()
                .getLocation();
            try (URLClassLoader loader = new URLClassLoader(new URL[] { classes }, getClass().getClassLoader()) {

                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (scenario == 5 && name.equals("java.awt.Rectangle"))
                        throw new java.awt.AWTError("Graphics unavailable");
                    if (name.equals("java.awt.Font")) throw new NoClassDefFoundError("Fonts unavailable");
                    if (!name.startsWith("pl.kuba6000.ae2webintegration.core.notification.destination.discord."))
                        return super.loadClass(name, resolve);
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }

                @Override
                public InputStream getResourceAsStream(String name) {
                    return name.endsWith(".ttf") ? null : super.getResourceAsStream(name);
                }
            }) {
                Class<?> destination = loader.loadClass(DiscordDestination.class.getName());
                destination.getMethod("sendNotification", IMessage.class)
                    .invoke(
                        destination.getConstructor()
                            .newInstance(),
                        message);
            }
            assertNotNull(body.get());
            JsonObject payload;
            if (scenario < 2) {
                assertTrue(
                    contentType.get()
                        .startsWith("multipart/form-data;"),
                    contentType.get());
                String wire = new String(body.get(), StandardCharsets.ISO_8859_1);
                String boundary = contentType.get()
                    .substring(
                        contentType.get()
                            .indexOf("boundary=") + 9);
                int start = wire.indexOf("\r\n\r\n") + 4;
                payload = new JsonParser().parse(wire.substring(start, wire.indexOf("\r\n--" + boundary, start)))
                    .getAsJsonObject();
                int pngStart = wire.indexOf("\r\n\r\n", wire.indexOf("name=\"files[0]\"")) + 4;
                int pngEnd = wire.indexOf("\r\n--" + boundary, pngStart);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(body.get(), pngStart, pngEnd - pngStart));
                assertNotNull(image);
                assertEquals(64, image.getWidth());
                assertEquals(64, image.getHeight());
                assertEquals(scenario == 0 ? 0xff1234ab : 0xffab3412, image.getRGB(32, 32));
                assertEquals(
                    "attachment://crafting.png",
                    payload.getAsJsonArray("embeds")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("thumbnail")
                        .get("url")
                        .getAsString());
                assertEquals(
                    "crafting.png",
                    payload.getAsJsonArray("attachments")
                        .get(0)
                        .getAsJsonObject()
                        .get("filename")
                        .getAsString());
            } else {
                assertEquals("application/json", contentType.get());
                payload = new JsonParser().parse(new String(body.get(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
                assertEquals(
                    0,
                    payload.getAsJsonArray("attachments")
                        .size());
                assertFalse(
                    payload.getAsJsonArray("embeds")
                        .get(0)
                        .getAsJsonObject()
                        .has("thumbnail"));
            }
            assertEquals(
                "<@&12345>",
                payload.get("content")
                    .getAsString());
            JsonObject embed = payload.getAsJsonArray("embeds")
                .get(0)
                .getAsJsonObject();
            assertFalse(embed.has("image"));
            assertTrue(
                embed.get("description")
                    .getAsString()
                    .contains("Rail x64"));
            assertTrue(
                embed.get("description")
                    .getAsString()
                    .contains("Automation, Web"));
        } finally {
            endpoint.stop(0);
        }
        CoreEngine.onServerStopped();
        if (scenario != 3) Files.delete(directory.resolve("ae2webintegration/icons.ae2wi-icons"));
    }

    @Test
    void homeRecentFlowUsesDetachedTrackingWithoutInventoryOrGameDispatch() throws Exception {
        port = HttpServerTestFixture.startWorld(config);
        AtomicBoolean permitted = new AtomicBoolean(true);
        Resource iron = new Resource("home-absent", 0, false);
        Grid storage = new Grid(88501);
        TestGrid grid = new TestGrid(88502, false, AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public IAEStorageGrid web$getStorageGrid() {
                return storage;
            }

            @Override
            public boolean web$hasAccess(@NotNull UUID player) {
                return permitted.get() && super.web$hasAccess(player);
            }
        };
        StableKey gridKey = TestGridFixtures.resolvedKey(grid);
        StableKey item = iron.web$getKey();
        GridSettingsData settings = CoreEngine.GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings();
        settings.setFlowTracked(true);
        AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
        CoreEngine.onServerTick();
        StorageCapture capture = storage.web$getStorageCapture();
        assertNotNull(capture);
        capture.recordFlow(iron, 17, 5);
        WebPrincipal principal = TestGridFixtures.principal(TestGridFixtures.OWNER_ID);
        ResourcePinStore pins = CoreEngine.getResourcePins();
        assertNotNull(pins);
        assertTrue(
            pins.add(
                principal,
                gridKey,
                item,
                new ResourceDescriptor("test", "home-absent", 0, 0, "Absent", ResourceType.ITEM, item, item),
                Placement.HOME));
        ApiRouter router = new ApiRouter(
            exchange -> new AE2Controller.RequestContext(exchange, principal),
            exchange -> false,
            request -> fail("Home must never dispatch game-thread work"));
        router.register(GetResourcePins.class);
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/api", router);
        endpoint.start();
        int originalPort = port;
        port = endpoint.getAddress()
            .getPort();
        try {
            JsonObject entry = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                CoreEngine.onServerTick();
                entry = json(connection("/api/resource-pins")).getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject();
                assertTrue(entry.has("recent"));
                if (!entry.get("recent")
                    .isJsonNull()) {
                    JsonObject amount = entry.getAsJsonObject("recent")
                        .getAsJsonObject("resources")
                        .getAsJsonObject(item.toString());
                    if (!amount.get("input")
                        .isJsonNull() && amount.get("input")
                            .getAsString()
                            .equals("17"))
                        break;
                }
                Thread.sleep(10);
            }
            assertNotNull(entry);
            JsonObject recent = entry.getAsJsonObject("recent");
            assertNotNull(recent);
            assertEquals(
                "17",
                recent.getAsJsonObject("resources")
                    .getAsJsonObject(item.toString())
                    .get("input")
                    .getAsString());
            assertEquals(
                "5",
                recent.getAsJsonObject("resources")
                    .getAsJsonObject(item.toString())
                    .get("output")
                    .getAsString());
            assertTrue(
                recent.get("stockSince")
                    .isJsonNull());
            assertTrue(
                recent.get("stockObservedAt")
                    .isJsonNull());
            assertTrue(
                recent.getAsJsonObject("resources")
                    .getAsJsonObject(item.toString())
                    .get("stockChange")
                    .isJsonNull());
            assertNull(
                CoreEngine.getTerminalRegistry()
                    .snapshot(gridKey));
            int reads = storage.storageReads;
            assertEquals(
                entry,
                json(connection("/api/resource-pins")).getAsJsonArray("data")
                    .get(0));
            assertEquals(reads, storage.storageReads, "HTTP only reads detached RAM and persisted pin metadata");
            Pin savedPin = pins.list(principal)
                .get(0);
            HomeConfig current = savedPin.homeConfig();
            HomeConfig muted = new HomeConfig(
                current.showStock(),
                current.showFlow(),
                current.showChart(),
                current.stock(),
                current.input(),
                current.output(),
                current.net(),
                current.rangeMillis(),
                false,
                current.flowRangeMillis());
            assertTrue(pins.update(principal, savedPin.pinKey(), null, null, null, muted));
            assertTrue(
                json(connection("/api/resource-pins")).getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject()
                    .get("recent")
                    .isJsonNull());
            assertTrue(pins.update(principal, savedPin.pinKey(), true, false, null, current));
            assertTrue(
                json(connection("/api/resource-pins")).getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject()
                    .get("recent")
                    .isJsonNull());
            assertTrue(pins.update(principal, savedPin.pinKey(), false, true, null, null));
            permitted.set(false);
            JsonObject hidden = json(connection("/api/resource-pins")).getAsJsonArray("data")
                .get(0)
                .getAsJsonObject();
            assertTrue(
                hidden.get("recent")
                    .isJsonNull());
            assertTrue(
                hidden.get("resource")
                    .isJsonNull());
            permitted.set(true);
            settings.setFlowTracked(false);
            assertTrue(
                json(connection("/api/resource-pins")).getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject()
                    .get("recent")
                    .isJsonNull(),
                "Disabling flow hides old publication before discovery");
            long stopped = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do {
                CoreEngine.onServerTick();
                entry = json(connection("/api/resource-pins")).getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject();
                if (entry.get("recent")
                    .isJsonNull()) break;
                Thread.sleep(10);
            } while (System.nanoTime() < stopped);
            assertTrue(
                entry.get("recent")
                    .isJsonNull());
        } finally {
            port = originalPort;
            endpoint.stop(0);
        }
    }

    @Test
    void playerPermissionRevocationRedactsPinsButOwnershipStillAllowsRemoval() throws Exception {
        port = HttpServerTestFixture.startWorld(config);
        AtomicBoolean permitted = new AtomicBoolean(true);
        TestGrid grid = new TestGrid(88403, false, AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public boolean web$hasAccess(@NotNull UUID player) {
                return permitted.get() && super.web$hasAccess(player);
            }
        };
        StableKey gridKey = TestGridFixtures.resolvedKey(grid);
        StableKey item = TestGridFixtures.key(82);
        long now = System.currentTimeMillis();
        TerminalRegistry terminals = CoreEngine.getTerminalRegistry();
        assertNotNull(terminals);
        terminals.activate(gridKey, 1, now);
        terminals.observed(
            gridKey,
            1,
            new StorageObservation(
                now,
                true,
                true,
                Collections.singletonList(new StorageObservation.Row(item, 9, false)),
                Collections.emptyList(),
                Collections.singletonMap(
                    item,
                    new ResourceDescriptor("test", "gold", 0, 0, "Gold", ResourceType.ITEM, item, item)),
                Collections.emptyList()));
        AtomicReference<WebPrincipal> principal = new AtomicReference<>(
            TestGridFixtures.principal(TestGridFixtures.OWNER_ID));
        ApiRouter router = new ApiRouter(
            exchange -> new AE2Controller.RequestContext(exchange, principal.get()),
            exchange -> false,
            request -> fail("Pins must not dispatch native game work"));
        router.register(GetResourcePins.class);
        router.register(PostResourcePin.class);
        router.register(PatchResourcePin.class);
        router.register(DeleteResourcePin.class);
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/api", router);
        endpoint.start();
        int originalPort = port;
        port = endpoint.getAddress()
            .getPort();
        try {
            PinReply reply = pinRequest(
                "POST",
                "/api/grids/" + gridKey + "/resource-pins",
                "{\"itemKey\":\"" + item + "\",\"placement\":\"home\"}",
                true);
            assertEquals(200, reply.code());
            String pinKey = reply.body()
                .getAsJsonArray("data")
                .get(0)
                .getAsJsonObject()
                .get("pinKey")
                .getAsString();
            principal.set(TestGridFixtures.principal(42));
            assertEquals(
                0,
                json(connection("/api/resource-pins")).getAsJsonArray("data")
                    .size());
            assertEquals(404, pinRequest("PATCH", "/api/resource-pins/" + pinKey, "{\"home\":false}", true).code());
            assertEquals(200, pinRequest("DELETE", "/api/resource-pins/" + pinKey, "", true).code());
            principal.set(TestGridFixtures.principal(TestGridFixtures.OWNER_ID));
            permitted.set(false);
            assertSame(grid, CoreEngine.GRID_IDENTITIES.getGrid(gridKey));
            JsonObject unavailable = json(connection("/api/resource-pins?icons=true")).getAsJsonArray("data")
                .get(0)
                .getAsJsonObject();
            assertFalse(
                unavailable.get("available")
                    .getAsBoolean());
            for (String field : Arrays.asList("gridKey", "itemKey", "gridName", "resource")) assertTrue(
                unavailable.get(field)
                    .isJsonNull());
            assertEquals(403, pinRequest("PATCH", "/api/resource-pins/" + pinKey, "{\"terminal\":true}", true).code());
            assertEquals(200, pinRequest("DELETE", "/api/resource-pins/" + pinKey, "", true).code());
            assertEquals(
                0,
                json(connection("/api/resource-pins")).getAsJsonArray("data")
                    .size());
        } finally {
            port = originalPort;
            endpoint.stop(0);
        }
    }

    @Test
    void resourcePinsUseDetachedMembershipAndRemainRemovableAfterGridRetirement() throws Exception {
        port = HttpServerTestFixture.startWorld(config);
        TestGrid grid = new TestGrid(88401, false, AEControllerState.CONTROLLER_ONLINE);
        TestGrid other = new TestGrid(88402, false, AEControllerState.CONTROLLER_ONLINE);
        StableKey gridKey = TestGridFixtures.resolvedKey(grid);
        StableKey otherKey = TestGridFixtures.resolvedKey(other);
        StableKey item = TestGridFixtures.key(81);
        TerminalRegistry terminals = CoreEngine.getTerminalRegistry();
        assertNotNull(terminals);
        long now = System.currentTimeMillis();
        terminals.activate(gridKey, 1, now);
        terminals.observed(
            gridKey,
            1,
            new StorageObservation(
                now,
                true,
                true,
                Collections.singletonList(new StorageObservation.Row(item, 12, false)),
                Collections.emptyList(),
                Collections.singletonMap(
                    item,
                    new ResourceDescriptor("test", "iron", 0, 0, "Iron", ResourceType.ITEM, item, item)),
                Collections.emptyList()));
        String body = "{\"itemKey\":\"" + item + "\",\"placement\":\"terminal\"}";
        String path = "/api/grids/" + gridKey + "/resource-pins";
        assertEquals(403, pinRequest("POST", path, body, false).code());
        PinReply created = pinRequest("POST", path, body, true);
        assertEquals(200, created.code());
        JsonObject pin = created.body()
            .getAsJsonArray("data")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "Iron",
            pin.getAsJsonObject("resource")
                .get("displayName")
                .getAsString());
        String pinKey = pin.get("pinKey")
            .getAsString();
        assertEquals(404, pinRequest("POST", "/api/grids/" + otherKey + "/resource-pins", body, true).code());
        terminals.retire(gridKey, 1);
        assertEquals(200, pinRequest("POST", path, body.replace("terminal", "home"), true).code());
        assertEquals(
            400,
            pinRequest(
                "PATCH",
                "/api/resource-pins/" + pinKey,
                "{\"terminal\":false,\"homeConfig\":{\"showStock\":true}}",
                true).code());
        assertEquals(
            0,
            json(connection("/api/resource-pins")).getAsJsonArray("data")
                .get(0)
                .getAsJsonObject()
                .get("terminalOrder")
                .getAsInt());
        PinReply configured = pinRequest(
            "PATCH",
            "/api/resource-pins/" + pinKey,
            "{\"homeConfig\":{\"showStock\":false,\"showFlow\":true,\"showChart\":true,\"stock\":false,\"input\":true,\"output\":false,\"net\":true,\"rangeMillis\":900000,\"showRecentFlow\":true,\"flowRangeMillis\":3600000},"
                + "\"move\":{\"placement\":\"home\",\"direction\":\"later\"}}",
            true);
        assertEquals(200, configured.code());
        assertEquals(
            900000,
            configured.body()
                .getAsJsonArray("data")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("homeConfig")
                .get("rangeMillis")
                .getAsLong());
        JsonObject beforeInvalid = configured.body();
        String validConfig = "\"showStock\":false,\"showFlow\":true,\"showChart\":true,\"stock\":false,\"input\":true,\"output\":false,\"net\":true,\"rangeMillis\":900000,\"showRecentFlow\":true,\"flowRangeMillis\":3600000";
        String excessiveDepth = "{}";
        for (int level = 0; level < 12; level++) excessiveDepth = "{\"nested\":" + excessiveDepth + "}";
        for (String malformed : Arrays.asList(
            "{\"terminal\":false,\"homeConfig\":{" + validConfig + ",\"net\":false}}",
            "{\"terminal\":false,\"move\":{\"placement\":\"home\",\"direction\":\"earlier\",\"direction\":\"later\"}}",
            "{\"terminal\":false,\"homeConfig\":[]}",
            "{\"terminal\":false,\"homeConfig\":" + excessiveDepth + "}")) {
            assertEquals(400, pinRequest("PATCH", "/api/resource-pins/" + pinKey, malformed, true).code());
            assertEquals(beforeInvalid, json(connection("/api/resource-pins")));
        }
        CoreEngine.GRID_IDENTITIES.removeController(
            grid.web$getControllers()
                .iterator()
                .next());
        JsonObject unavailable = json(connection("/api/resource-pins?icons=true")).getAsJsonArray("data")
            .get(0)
            .getAsJsonObject();
        assertFalse(
            unavailable.get("available")
                .getAsBoolean());
        for (String field : Arrays.asList("gridKey", "itemKey", "gridName", "resource")) assertTrue(
            unavailable.get(field)
                .isJsonNull());
        assertEquals(200, pinRequest("DELETE", "/api/resource-pins/" + pinKey, "", true).code());
        assertEquals(
            0,
            json(connection("/api/resource-pins")).getAsJsonArray("data")
                .size());
    }

    @Desugar
    private record PinReply(int code, JsonObject body) {}

    private PinReply pinRequest(String method, String path, String body, boolean csrf) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String request = method + " "
                + path
                + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + "Content-Type: application/json\r\n"
                + (csrf ? "X-AE2-Request: true\r\n" : "")
                + "Content-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n"
                + body;
            socket.getOutputStream()
                .write(request.getBytes(StandardCharsets.UTF_8));
            String response = IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
            return new PinReply(
                Integer.parseInt(response.split(" ", 3)[1]),
                new Gson().fromJson(response.substring(response.indexOf("\r\n\r\n") + 4), JsonObject.class));
        }
    }

    @Test
    void retentionPatchAppliesToInactiveHistoryAndSurvivesRestart() throws Exception {
        port = HttpServerTestFixture.startWorld(config);
        TestGrid grid = new TestGrid(81079, false, AEControllerState.CONTROLLER_ONLINE);
        StableKey gridKey = TestGridFixtures.resolvedKey(grid);
        StableKey item = TestGridFixtures.key(121);
        StorageTrackingService service = CoreEngine.getStorageTracking();
        assertNotNull(service);
        long observedAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
        service.open(gridKey, 1);
        assertTrue(
            service.submit(
                gridKey,
                1,
                new StorageObservation(
                    observedAt,
                    true,
                    true,
                    Collections.singletonList(new StorageObservation.Row(item, 5, false)),
                    Collections.emptyList(),
                    Collections.singletonMap(
                        item,
                        new ResourceDescriptor("test", "item", 0, 0, "Item", ResourceType.ITEM, item, null)),
                    Collections.emptyList()),
                true,
                false,
                observedAt));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.history(gridKey, item, 0, 100)
            .points()
            .isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(
            service.history(gridKey, item, 0, 100)
                .points()
                .isEmpty());
        service.retire(gridKey, 1);
        String path = "/api/grids/" + gridKey;
        assertFalse(
            json(connection(path + "/resource-history")).getAsJsonObject("data")
                .getAsJsonArray("points")
                .size() == 0);
        assertEquals(200, patchRetention(path, 1));
        assertTrue(
            json(connection(path + "/resource-history")).getAsJsonObject("data")
                .getAsJsonArray("points")
                .size() == 0);
        CoreEngine.onServerStopped();
        port = HttpServerTestFixture.startWorld(config);
        assertEquals(gridKey, TestGridFixtures.resolvedKey(grid));
        assertTrue(
            json(connection(path + "/resource-history")).getAsJsonObject("data")
                .getAsJsonArray("points")
                .size() == 0);
    }

    private int patchRetention(String path, int days) throws Exception {
        String body = "{\"historyRetentionDays\":" + days + "}";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String request = "PATCH " + path
                + "/settings HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + "Content-Type: application/json\r\nX-AE2-Request: true\r\nContent-Length: "
                + body.length()
                + "\r\n\r\n"
                + body;
            socket.getOutputStream()
                .write(request.getBytes(StandardCharsets.UTF_8));
            String response = IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
            return Integer.parseInt(response.split(" ", 3)[1]);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void resourceHistoryResolvesRequestedIconsFromSavedKeysWithoutNativeReads(boolean installed) throws Exception {
        StableKey exact = TestGridFixtures.key(121);
        StableKey fallback = TestGridFixtures.key(122);
        StableKey missing = TestGridFixtures.key(123);
        if (installed) installPack(exact);
        port = HttpServerTestFixture.startWorld(config);
        TestGrid grid = new TestGrid(81078, false, AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public IAEStorageGrid web$getStorageGrid() {
                throw new AssertionError("Async history must not read native storage");
            }

            @Override
            public IAECraftingGrid web$getCraftingGrid() {
                throw new AssertionError("Async history must not read native crafting");
            }
        };
        StableKey gridKey = TestGridFixtures.resolvedKey(grid);
        StorageTrackingService service = CoreEngine.getStorageTracking();
        assertNotNull(service);
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        descriptions
            .put(exact, new ResourceDescriptor("test", "exact", 0, 0, "Exact", ResourceType.ITEM, exact, missing));
        descriptions.put(
            fallback,
            new ResourceDescriptor("test", "fallback", 0, 2, "Fallback", ResourceType.ITEM, fallback, exact));
        descriptions
            .put(missing, new ResourceDescriptor("test", "missing", 0, 0, "Missing", ResourceType.ITEM, missing, null));
        long observedAt = System.currentTimeMillis();
        service.open(gridKey, 1);
        assertTrue(
            service.submit(
                gridKey,
                1,
                new StorageObservation(
                    observedAt,
                    true,
                    true,
                    Arrays.asList(
                        new StorageObservation.Row(exact, 5, false),
                        new StorageObservation.Row(fallback, Long.MAX_VALUE, false),
                        new StorageObservation.Row(missing, 7, false)),
                    Collections
                        .singletonList(new StorageObservation.Flow(exact, Long.MAX_VALUE, 6, Long.MAX_VALUE - 1, 2)),
                    descriptions,
                    Collections.emptyList()),
                true,
                true,
                observedAt - 60000));
        awaitHistory(
            service,
            gridKey,
            observedAt,
            history -> history.points()
                .stream()
                .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.FLOW));
        AE2Controller.itemIdentities.clear();
        String path = "/api/grids/" + gridKey + "/resource-history";
        JsonObject plain = json(connection(path));
        for (JsonElement point : plain.getAsJsonObject("data")
            .getAsJsonArray("points")) {
            JsonObject measured = point.getAsJsonObject();
            String kind = measured.get("kind")
                .getAsString();
            assertNotNull(measured.get("inputOperations"));
            assertTrue(
                measured.get("inputOperations")
                    .getAsJsonPrimitive()
                    .isString());
            assertTrue(
                measured.get("outputOperations")
                    .getAsJsonPrimitive()
                    .isString());
            assertEquals(
                kind.equals("FLOW") ? "9223372036854775806" : "0",
                measured.get("inputOperations")
                    .getAsString());
            assertEquals(
                kind.equals("FLOW") ? "2" : "0",
                measured.get("outputOperations")
                    .getAsString());
            if (kind.equals("FLOW") || kind.equals("FLOW_OBSERVATION")) assertEquals(
                observedAt - 60000,
                measured.get("flowFrom")
                    .getAsLong());
            else assertTrue(
                measured.get("flowFrom")
                    .isJsonNull());
            assertFalse(
                point.getAsJsonObject()
                    .has("craftable"));
            assertFalse(
                point.getAsJsonObject()
                    .has("complete"));
            JsonElement resource = point.getAsJsonObject()
                .get("resource");
            if (!resource.isJsonNull()) assertTrue(
                resource.getAsJsonObject()
                    .get("icon")
                    .isJsonNull());
        }
        JsonObject response = json(connection(path + "?icons=true"));
        assertTrue(response.has("icons"));
        if (installed) {
            assertEquals(
                packId,
                response.getAsJsonObject("icons")
                    .get("packId")
                    .getAsString());
            assertEquals(
                1,
                response.getAsJsonObject("icons")
                    .getAsJsonArray("pages")
                    .size());
        } else assertTrue(
            response.get("icons")
                .isJsonNull());
        for (JsonElement point : response.getAsJsonObject("data")
            .getAsJsonArray("points")) {
            JsonElement resource = point.getAsJsonObject()
                .get("resource");
            if (resource.isJsonNull()) continue;
            JsonObject row = resource.getAsJsonObject();
            boolean expectedIcon = installed && !missing.toString()
                .equals(
                    row.get("itemKey")
                        .getAsString());
            assertEquals(
                !expectedIcon,
                row.get("icon")
                    .isJsonNull());
            if (expectedIcon) assertEquals(
                0,
                row.getAsJsonObject("icon")
                    .get("page")
                    .getAsInt());
        }
        JsonObject baselineResponse = json(connection(path + "?icons=true&itemKey=" + fallback + "&limit=1"));
        assertFalse(
            baselineResponse.getAsJsonObject("data")
                .getAsJsonObject("baseline")
                .has("craftable"));
        assertFalse(
            baselineResponse.getAsJsonObject("data")
                .getAsJsonObject("baseline")
                .has("complete"));
        JsonObject baseline = baselineResponse.getAsJsonObject("data")
            .getAsJsonObject("baseline")
            .getAsJsonObject("resource");
        assertEquals(
            "9223372036854775807",
            baseline.get("quantity")
                .getAsString());
        assertEquals(
            !installed,
            baseline.get("icon")
                .isJsonNull());
        JsonObject disabled = json(connection(path + "?icons=false"));
        assertTrue(
            disabled.get("icons")
                .isJsonNull());
        assertSame(grid, CoreEngine.GRID_IDENTITIES.getGrid(gridKey));
    }

    @Test
    void installedPackPublishesAvailabilityAndAuthenticatedImmutablePages() throws Exception {
        installPack();
        port = HttpServerTestFixture.startWorld(config);
        HttpURLConnection discovery = connection("/api/icon-pack");
        JsonObject data = json(discovery).getAsJsonObject("data");
        assertTrue(
            data.get("available")
                .getAsBoolean());
        assertEquals(
            packId,
            data.get("packId")
                .getAsString());
        assertEquals(
            64,
            data.get("width")
                .getAsInt());
        assertEquals(
            64,
            data.get("height")
                .getAsInt());
        HttpURLConnection page = connection(pagePath());
        assertEquals(200, page.getResponseCode());
        assertEquals("image/png", page.getHeaderField("Content-Type"));
        assertEquals("\"" + digest + "\"", page.getHeaderField("ETag"));
        assertEquals("private, max-age=31536000, immutable", page.getHeaderField("Cache-Control"));
        assertEquals(png.length, page.getContentLength());
        try (InputStream body = page.getInputStream()) {
            assertArrayEquals(png, IOUtils.toByteArray(body));
        }
        HttpURLConnection cached = connection(pagePath());
        cached.setRequestProperty("If-None-Match", "W/\"" + digest + "\"");
        assertEquals(304, cached.getResponseCode());
        cached.disconnect();
        HttpURLConnection head = connection(pagePath());
        head.setRequestMethod("HEAD");
        assertEquals(200, head.getResponseCode());
        assertEquals(png.length, head.getContentLength());
        try (InputStream body = head.getInputStream()) {
            assertEquals(0, IOUtils.toByteArray(body).length);
        }
        config.write("general.allow_no_password_on_localhost", false);
        Config.reload();
        HttpURLConnection denied = connection(pagePath());
        denied.setRequestProperty("If-None-Match", "\"" + digest + "\"");
        assertEquals(401, denied.getResponseCode());
        assertEquals("no-store", denied.getHeaderField("Cache-Control"));
        denied.disconnect();
    }

    @Test
    void packIsDiscoveredByExtensionWithoutRenamingTheExport() throws Exception {
        installPack();
        Path folder = directory.resolve("ae2webintegration");
        Files.move(folder.resolve("icons.ae2wi-icons"), folder.resolve("AE2WebIntegration-icons-export.ae2wi-icons"));
        Files.write(folder.resolve("old.ae2wi-icons.bak"), new byte[] { 1 });
        Files.createDirectory(folder.resolve("folder.ae2wi-icons"));
        port = HttpServerTestFixture.startWorld(config);

        JsonObject data = json(connection("/api/icon-pack")).getAsJsonObject("data");
        assertTrue(
            data.get("available")
                .getAsBoolean());
        assertEquals(
            packId,
            data.get("packId")
                .getAsString());
        assertEquals(200, connection(pagePath()).getResponseCode());
    }

    @Test
    void multiplePacksDisableIconsUntilTheConflictIsRemoved() throws Exception {
        installPack();
        Path folder = directory.resolve("ae2webintegration");
        Path extra = folder.resolve("another-export.ae2wi-icons");
        Files.copy(folder.resolve("icons.ae2wi-icons"), extra);
        port = HttpServerTestFixture.startWorld(config);

        JsonObject data = json(connection("/api/icon-pack")).getAsJsonObject("data");
        assertFalse(
            data.get("available")
                .getAsBoolean());
        assertEquals(200, connection("/assets/web/app/main.js").getResponseCode());
        CoreEngine.onServerStopped();
        Files.delete(extra);
        port = HttpServerTestFixture.startWorld(config);
        assertTrue(
            json(connection("/api/icon-pack")).getAsJsonObject("data")
                .get("available")
                .getAsBoolean());
    }

    @Test
    void absentInvalidAndMismatchedPacksLeaveNormalServerAvailableAcrossRestarts() throws Exception {
        port = HttpServerTestFixture.startWorld(config);
        assertFalse(
            json(connection("/api/icon-pack")).getAsJsonObject("data")
                .get("available")
                .getAsBoolean());
        CoreEngine.onServerStopped();
        Path installed = directory.resolve("ae2webintegration/icons.ae2wi-icons");
        Files.write(installed, new byte[] { 1, 2, 3 });
        port = HttpServerTestFixture.startWorld(config);
        JsonObject invalid = json(connection("/api/icon-pack")).getAsJsonObject("data");
        assertFalse(
            invalid.get("available")
                .getAsBoolean());
        assertTrue(
            invalid.get("packId")
                .isJsonNull());
        assertEquals(200, connection("/assets/web/app/main.js").getResponseCode());
        CoreEngine.onServerStopped();
        Files.delete(installed);
        installPack();
        port = HttpServerTestFixture.startWorld(config);
        assertTrue(
            json(connection("/api/icon-pack")).getAsJsonObject("data")
                .get("available")
                .getAsBoolean());
        String other = String.join("", Collections.nCopies(64, "f"));
        for (String path : new String[] { "/api/icon-packs/" + other + "/pages/" + digest,
            "/api/icon-packs/" + packId + "/pages/" + other }) {
            HttpURLConnection missing = connection(path);
            assertEquals(404, missing.getResponseCode());
            assertEquals("no-store", missing.getHeaderField("Cache-Control"));
            assertTrue(
                missing.getHeaderField("Content-Type")
                    .startsWith("application/json"));
            missing.disconnect();
        }
        HttpURLConnection malformed = connection("/api/icon-packs/not-a-digest/pages/" + digest);
        assertEquals(400, malformed.getResponseCode());
        malformed.disconnect();
        CoreEngine.onServerStopped();
        Files.delete(installed);
        port = HttpServerTestFixture.startWorld(config);
        assertFalse(
            json(connection("/api/icon-pack")).getAsJsonObject("data")
                .get("available")
                .getAsBoolean());
    }

    @Test
    void incompatibleTargetDisablesPackWithoutPublishingItsPages() throws Exception {
        installPack();
        IServerPlatform platform = AE2Controller.serverPlatform;
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public java.nio.file.Path getModFile() {
                return null;
            }

            @Override
            public boolean isSingleplayer() {
                return false;
            }

            @Override
            public @NotNull String getModVersion() {
                return "test-version";
            }

            public UUID getOnlinePlayerUUID(String username) {
                return null;
            }

            public File getConfigDirectory() {
                return directory.toFile();
            }

            public ILegacyConfigProvider getLegacyConfig() {
                return null;
            }

            public File getWorldDirectory() {
                return platform.getWorldDirectory();
            }

            public @NotNull String getMinecraftVersion() {
                return "1.21.1";
            }

            public @NotNull String getLoader() {
                return "neoforge";
            }

            public @NotNull String getIconPackCompatibilityVersion() {
                return "other";
            }
        };
        port = HttpServerTestFixture.startWorld(config);
        assertFalse(
            json(connection("/api/icon-pack")).getAsJsonObject("data")
                .get("available")
                .getAsBoolean());
        HttpURLConnection page = connection(pagePath());
        assertEquals(404, page.getResponseCode());
        page.disconnect();
    }

    @Test
    void inventoryMappingsAreOptInExactFirstAndMemoizedWithoutChangingQuantities() throws Exception {
        StableKey exactKey = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        StableKey baseKey = StableKey.parse("AQEBAQEBAQEBAQEBAQEBAQ");
        installPack(exactKey, baseKey);
        port = HttpServerTestFixture.startWorld(config);
        AtomicInteger normalized = new AtomicInteger();
        Resource exact = iconResource("exact", 12, exactKey, baseKey, normalized);
        Resource fallback = iconResource("worn", 7, TestGridFixtures.key(72), baseKey, normalized);
        Resource missing = new Resource("missing", 3, false);
        Grid grid = new Grid(31, exact, fallback, missing);
        AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
        String path = "/api/grids/" + TestGridFixtures.resolvedKey(grid) + "/items";
        JsonObject plain = syncedJson(path);
        assertTrue(
            plain.has("icons") && plain.get("icons")
                .isJsonNull());
        assertEquals(2, normalized.get());
        for (int poll = 0; poll < 2; poll++) {
            JsonObject response = syncedJson(path + "?icons=true");
            JsonObject icons = response.getAsJsonObject("icons");
            assertEquals(
                packId,
                icons.get("packId")
                    .getAsString());
            assertEquals(
                1,
                icons.getAsJsonArray("pages")
                    .size());
            assertEquals(
                3,
                response.getAsJsonArray("data")
                    .size());
            JsonObject first = response.getAsJsonArray("data")
                .get(0)
                .getAsJsonObject();
            JsonObject second = response.getAsJsonArray("data")
                .get(1)
                .getAsJsonObject();
            assertEquals(
                exactKey.toString(),
                first.get("itemKey")
                    .getAsString());
            assertEquals(
                12,
                first.get("quantity")
                    .getAsLong());
            assertEquals(
                7,
                second.get("quantity")
                    .getAsLong());
            assertEquals(first.get("icon"), second.get("icon"));
            assertEquals(
                0,
                first.getAsJsonObject("icon")
                    .get("page")
                    .getAsInt());
            assertTrue(
                response.getAsJsonArray("data")
                    .get(2)
                    .getAsJsonObject()
                    .get("icon")
                    .isJsonNull());
        }
        assertEquals(2, normalized.get(), "Descriptions normalize once independently of icon requests");
        syncedJson(path + "?icons=false");
        assertEquals(2, normalized.get());
        HttpURLConnection invalid = connection(path + "?icons=perhaps");
        assertEquals(400, invalid.getResponseCode());
        invalid.disconnect();
    }

    private Resource iconResource(String id, long quantity, StableKey exact, StableKey base, AtomicInteger calls) {
        return new Resource(id, quantity, false) {

            public @NotNull IAEKey web$copyIdentity() {
                return this;
            }

            public @NotNull StableKey web$getKey() {
                return exact;
            }

            public StableKey web$getBaseKey() {
                calls.incrementAndGet();
                return base;
            }
        };
    }

    @Test
    void planIconsAreOptInAndRetainFallbackAcrossInventoryRefresh() throws Exception {
        StableKey exact = TestGridFixtures.key(151);
        StableKey base = TestGridFixtures.key(152);
        installPack(exact, base);
        port = HttpServerTestFixture.startWorld(config);
        AtomicInteger normalizations = new AtomicInteger();
        IAEKey[] resources = { iconResource("exact", 12, exact, base, normalizations),
            iconResource("fallback", 12, TestGridFixtures.key(153), base, normalizations),
            new Resource("unavailable", 12, false) {

                @Override
                public @NotNull ResourceType web$getResourceType() {
                    return ResourceType.OTHER;
                }

                @Override
                public @NotNull IAEKey web$copyIdentity() {
                    return this;
                }

                @Override
                public StableKey web$getBaseKey() {
                    throw new IllegalStateException("Unsupported icon");
                }
            } };
        TestGrid grid = new TestGrid(154, false, AEControllerState.CONTROLLER_ONLINE) {

            private StorageCapture storageCapture;

            @Override
            public IAECraftingGrid web$getCraftingGrid() {
                return (IAECraftingGrid) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { IAECraftingGrid.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "web$getCraftables", "web$getCPUs" -> Collections.emptySet();
                        case "web$isCurrentlyCraftable" -> false;
                        default -> throw new AssertionError(method.getName());
                    });
            }

            @Override
            public IAEStorageGrid web$getStorageGrid() {
                return (IAEStorageGrid) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { IAEStorageGrid.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "web$getStorageList", "web$getStorageSnapshot" -> stacks();
                        case "web$getStorageCapture" -> storageCapture;
                        case "web$setStorageCapture" -> {
                            storageCapture = (StorageCapture) args[0];
                            yield null;
                        }
                        case "web$getInventory" -> Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] { IAEMeInventoryItem.class },
                            (inventory, operation, parameters) -> 0L);
                        default -> throw new AssertionError(method.getName());
                    });
            }
        };
        AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
        String gridPath = "/api/grids/" + TestGridFixtures.resolvedKey(grid);
        CompletableFuture<IAECraftingJob> future = new CompletableFuture<>();
        int id = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(future, ResourceSnapshot.capture(resources[0], 37), new CraftingOptions(false, false), false);
        String path = gridPath + "/crafting-plans/" + id;
        JsonObject pending = syncedJson(path + "?icons=true");
        assertFalse(
            pending.getAsJsonObject("data")
                .get("isDone")
                .getAsBoolean());
        assertTrue(
            pending.getAsJsonObject("data")
                .get("plan")
                .isJsonNull());
        JsonObject pendingOutput = pending.getAsJsonObject("data")
            .getAsJsonObject("finalOutput");
        assertEquals(
            exact.toString(),
            pendingOutput.get("itemKey")
                .getAsString());
        assertEquals(
            37,
            pendingOutput.get("quantity")
                .getAsLong());
        assertNotNull(pendingOutput.getAsJsonObject("icon"));
        assertEquals(
            0,
            pendingOutput.getAsJsonObject("icon")
                .get("page")
                .getAsInt());
        ICraftingPlanSummary summary = () -> Arrays.stream(resources)
            .map(
                resource -> (ICraftingPlanSummaryEntry) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { ICraftingPlanSummaryEntry.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "web$getWhat" -> resource;
                        case "web$getCraftAmount" -> 12L;
                        case "web$getCraftSteps" -> 3L;
                        case "web$getMissingAmount", "web$getStoredAmount" -> 0L;
                        default -> throw new AssertionError(method.getName());
                    }))
            .collect(Collectors.toList());
        future.complete(
            (IAECraftingJob) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IAECraftingJob.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "web$isSimulation" -> false;
                    case "web$getByteTotal" -> 4096L;
                    case "web$generateSummary" -> summary;
                    default -> throw new AssertionError(method.getName());
                }));
        JsonObject plain = syncedJson(path);
        assertTrue(
            plain.has("icons") && plain.get("icons")
                .isJsonNull());
        assertTrue(
            plain.getAsJsonObject("data")
                .getAsJsonObject("finalOutput")
                .get("icon")
                .isJsonNull());
        assertEquals(1, normalizations.get());
        for (int poll = 0; poll < 2; poll++) {
            JsonObject response = syncedJson(path + "?icons=true");
            assertEquals(
                packId,
                response.getAsJsonObject("icons")
                    .get("packId")
                    .getAsString());
            assertEquals(
                pendingOutput,
                response.getAsJsonObject("data")
                    .get("finalOutput"));
            JsonArray rows = response.getAsJsonObject("data")
                .getAsJsonArray("plan");
            assertEquals(3, rows.size());
            assertEquals(
                "ITEM",
                rows.get(0)
                    .getAsJsonObject()
                    .get("resourceType")
                    .getAsString());
            assertEquals(
                "OTHER",
                rows.get(2)
                    .getAsJsonObject()
                    .get("resourceType")
                    .getAsString());
            assertEquals(
                exact.toString(),
                rows.get(0)
                    .getAsJsonObject()
                    .get("itemKey")
                    .getAsString());
            assertNotNull(
                rows.get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("icon"));
            assertEquals(
                rows.get(0)
                    .getAsJsonObject()
                    .get("icon"),
                rows.get(1)
                    .getAsJsonObject()
                    .get("icon"));
            assertTrue(
                rows.get(2)
                    .getAsJsonObject()
                    .get("icon")
                    .isJsonNull());
            assertEquals(
                12,
                rows.get(1)
                    .getAsJsonObject()
                    .get("requested")
                    .getAsLong());
            syncedJson(gridPath + "/items");
            System.gc();
        }
        assertEquals(2, normalizations.get());
        JsonObject disabled = syncedJson(path + "?icons=false");
        assertTrue(
            disabled.get("icons")
                .isJsonNull());
        assertTrue(
            disabled.getAsJsonObject("data")
                .getAsJsonArray("plan")
                .get(0)
                .getAsJsonObject()
                .get("icon")
                .isJsonNull());
        HttpURLConnection invalid = connection(path + "?icons=perhaps");
        assertEquals(400, invalid.getResponseCode());
        invalid.disconnect();
        config.write("general.allow_no_password_on_localhost", false);
        Config.reload();
        HttpURLConnection denied = connection(path + "?icons=true");
        assertEquals(401, denied.getResponseCode());
        denied.disconnect();
    }

    @Test
    void cpuUsesIndependentIdentityOwnershipAndPreservesExistingAggregation() throws Exception {
        StableKey baseKey = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        StableKey exactKey = TestGridFixtures.key(91);
        StableKey cpuKey = TestGridFixtures.key(92);
        installPack(baseKey);
        port = HttpServerTestFixture.startWorld(config);
        AtomicInteger normalizations = new AtomicInteger();
        AtomicBoolean busy = new AtomicBoolean(true);
        Resource resource = iconResource("worn", 7, exactKey, baseKey, normalizations);
        AtomicReference<IAEGenericStack> output = new AtomicReference<>(
            iconResource("product", 11, baseKey, baseKey, normalizations));
        AtomicReference<IStackList> resources = new AtomicReference<>(stacks(resource, resource));
        ICraftingCPUCluster cpu = (ICraftingCPUCluster) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "web$getKey" -> cpuKey;
                case "web$isBusy" -> busy.get();
                case "web$acceptsPlayerJobs" -> true;
                case "web$getName" -> "CPU";
                case "web$getAvailableStorage" -> 1024L;
                case "web$getUsedStorage", "web$getCoProcessors" -> 0L;
                case "web$getFinalOutput" -> output.get();
                case "web$getItemLists" -> {
                    for (IAEGenericStack stack : resources.get()
                        .web$stacks()) {
                        IAEKey key = stack.web$what();
                        ((CpuFixtures.StackList) args[0]).amounts.merge(key, 6L, Long::sum);
                        ((CpuFixtures.StackList) args[1]).amounts.merge(key, 2L, Long::sum);
                        ((CpuFixtures.StackList) args[2]).amounts.merge(key, 3L, Long::sum);
                    }
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
        IAECraftingGrid crafting = (IAECraftingGrid) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { IAECraftingGrid.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "web$getCPUs" -> Collections.singleton(cpu);
                case "web$getCraftables" -> Collections.emptySet();
                case "web$isCurrentlyCraftable" -> false;
                default -> null;
            });
        TestGrid grid = new TestGrid(93, false, AEControllerState.CONTROLLER_ONLINE) {

            private StorageCapture storageCapture;

            public IAECraftingGrid web$getCraftingGrid() {
                return crafting;
            }

            public IAEStorageGrid web$getStorageGrid() {
                return (IAEStorageGrid) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { IAEStorageGrid.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "web$getStorageList", "web$getStorageSnapshot" -> stacks();
                        case "web$getStorageCapture" -> storageCapture;
                        case "web$setStorageCapture" -> {
                            storageCapture = (StorageCapture) args[0];
                            yield null;
                        }
                        default -> throw new AssertionError(method.getName());
                    });
            }
        };
        AE2Controller.AE2Interface = new TestGridFixtures.TestAE(grid) {

            public IStackList web$createStackList() {
                return new CpuFixtures.StackList();
            }
        };
        String gridPath = "/api/grids/" + TestGridFixtures.resolvedKey(grid);
        String path = gridPath + "/cpus/" + cpuKey;
        JsonObject overview = syncedJson(gridPath + "/cpus");
        assertTrue(
            overview.get("icons")
                .isJsonNull());
        assertEquals(1, normalizations.get());
        JsonObject enabledOverview = syncedJson(gridPath + "/cpus?icons=true");
        JsonObject product = enabledOverview.getAsJsonObject("data")
            .getAsJsonObject(cpuKey.toString());
        assertFalse(product.has("icon"), "Product icons belong to the resource, not its containing CPU");
        assertNotNull(
            product.getAsJsonObject("finalOutput")
                .getAsJsonObject("icon"));
        assertEquals(
            11,
            product.getAsJsonObject("finalOutput")
                .get("quantity")
                .getAsLong());
        assertEquals(
            packId,
            enabledOverview.getAsJsonObject("icons")
                .get("packId")
                .getAsString());
        assertEquals(1, normalizations.get());
        JsonObject plain = syncedJson(path);
        assertTrue(
            plain.has("icons") && plain.get("icons")
                .isJsonNull());
        assertFalse(
            plain.getAsJsonObject("data")
                .has("icon"));
        assertTrue(
            plain.getAsJsonObject("data")
                .getAsJsonObject("finalOutput")
                .get("icon")
                .isJsonNull());
        assertEquals(1, normalizations.get());
        for (int poll = 0; poll < 2; poll++) {
            JsonObject response = syncedJson(path + "?icons=true");
            JsonObject detail = response.getAsJsonObject("data");
            assertEquals(
                product.getAsJsonObject("finalOutput")
                    .get("icon"),
                detail.getAsJsonObject("finalOutput")
                    .get("icon"));
            assertEquals(
                baseKey.toString(),
                detail.getAsJsonObject("finalOutput")
                    .get("itemKey")
                    .getAsString());
            assertEquals(
                11,
                detail.getAsJsonObject("finalOutput")
                    .get("quantity")
                    .getAsLong());
            assertEquals(
                packId,
                response.getAsJsonObject("icons")
                    .get("packId")
                    .getAsString());
            JsonObject row = response.getAsJsonObject("data")
                .getAsJsonArray("items")
                .get(0)
                .getAsJsonObject();
            assertEquals(
                1,
                response.getAsJsonObject("data")
                    .getAsJsonArray("items")
                    .size());
            assertEquals(
                exactKey.toString(),
                row.get("itemKey")
                    .getAsString());
            assertNotNull(row.getAsJsonObject("icon"));
            assertEquals(
                12,
                row.get("active")
                    .getAsLong());
            assertEquals(
                4,
                row.get("pending")
                    .getAsLong());
            assertEquals(
                6,
                row.get("stored")
                    .getAsLong());
            JsonObject disabled = syncedJson(path + "?icons=false");
            assertTrue(
                disabled.get("icons")
                    .isJsonNull());
            assertTrue(
                disabled.getAsJsonObject("data")
                    .getAsJsonObject("finalOutput")
                    .get("icon")
                    .isJsonNull());
            syncedJson(gridPath + "/items?icons=true");
            System.gc();
        }
        assertEquals(2, normalizations.get());
        resources.set(stacks());
        JsonObject outputOnly = syncedJson(path + "?icons=true").getAsJsonObject("data");
        assertEquals(
            0,
            outputOnly.getAsJsonArray("items")
                .size());
        assertNotNull(
            outputOnly.getAsJsonObject("finalOutput")
                .getAsJsonObject("icon"));
        resources.set(stacks(resource, resource));
        busy.set(false);
        JsonObject idle = syncedJson(gridPath + "/cpus?icons=true").getAsJsonObject("data")
            .getAsJsonObject(cpuKey.toString());
        assertFalse(idle.has("icon"));
        assertTrue(
            idle.get("finalOutput")
                .isJsonNull());
        JsonObject idleDetail = syncedJson(path + "?icons=true").getAsJsonObject("data");
        assertTrue(
            idleDetail.get("items")
                .isJsonNull());
        assertTrue(
            idleDetail.get("finalOutput")
                .isJsonNull());
        assertFalse(idleDetail.has("icon"));

        busy.set(true);
        output.set(null);
        JsonObject unavailable = syncedJson(path + "?icons=true").getAsJsonObject("data");
        assertTrue(
            unavailable.get("finalOutput")
                .isJsonNull());
        assertFalse(unavailable.has("icon"));
        assertEquals(
            1,
            unavailable.getAsJsonArray("items")
                .size());

        int beforeProductFallback = normalizations.get();
        output.set(iconResource("fallback-product", 13, TestGridFixtures.key(94), baseKey, normalizations));
        for (int poll = 0; poll < 2; poll++) {
            JsonObject fallback = syncedJson(path + "?icons=true").getAsJsonObject("data");
            assertNotNull(
                fallback.getAsJsonObject("finalOutput")
                    .getAsJsonObject("icon"));
            assertEquals(
                13,
                fallback.getAsJsonObject("finalOutput")
                    .get("quantity")
                    .getAsLong());
        }
        assertEquals(
            beforeProductFallback + 1,
            normalizations.get(),
            "Repeated product polls reuse the retained fallback identity");

        output.set(
            iconResource("missing-product", 17, TestGridFixtures.key(95), TestGridFixtures.key(96), normalizations));
        JsonObject missing = syncedJson(path + "?icons=true").getAsJsonObject("data");
        assertTrue(
            missing.getAsJsonObject("finalOutput")
                .get("icon")
                .isJsonNull());
        assertEquals(
            17,
            missing.getAsJsonObject("finalOutput")
                .get("quantity")
                .getAsLong());
        assertEquals(
            1,
            missing.getAsJsonArray("items")
                .size());

        output.set(new Resource("unsupported-product", 19, false) {

            public @NotNull IAEKey web$copyIdentity() {
                return this;
            }

            public @NotNull StableKey web$getKey() {
                return TestGridFixtures.key(97);
            }

            public StableKey web$getBaseKey() {
                throw new IllegalStateException("Unsupported base identity");
            }
        });
        JsonObject unsupported = syncedJson(path + "?icons=true").getAsJsonObject("data");
        assertTrue(
            unsupported.getAsJsonObject("finalOutput")
                .get("icon")
                .isJsonNull());
        assertEquals(
            19,
            unsupported.getAsJsonObject("finalOutput")
                .get("quantity")
                .getAsLong());
        assertEquals(
            TestGridFixtures.key(97)
                .toString(),
            unsupported.getAsJsonObject("finalOutput")
                .get("itemKey")
                .getAsString());

        output.set(new Resource("unidentified-product", 23, false) {

            public @NotNull IAEKey web$copyIdentity() {
                throw new IllegalStateException("Unsupported identity");
            }
        });
        JsonObject unidentified = syncedJson(path + "?icons=true").getAsJsonObject("data");
        assertTrue(
            unidentified.getAsJsonObject("finalOutput")
                .get("icon")
                .isJsonNull());
        assertTrue(
            unidentified.getAsJsonObject("finalOutput")
                .get("itemKey")
                .isJsonNull());
        assertEquals(
            23,
            unidentified.getAsJsonObject("finalOutput")
                .get("quantity")
                .getAsLong());

        CoreEngine.onServerStopped();
        Files.delete(directory.resolve("ae2webintegration/icons.ae2wi-icons"));
        port = HttpServerTestFixture.startWorld(config);
        output.set(iconResource("product", 11, baseKey, baseKey, normalizations));
        int previousNormalizations = normalizations.get();
        JsonObject noPack = syncedJson(
            "/api/grids/" + TestGridFixtures.resolvedKey(grid) + "/cpus/" + cpuKey + "?icons=true");
        assertTrue(
            noPack.get("icons")
                .isJsonNull());
        assertTrue(
            noPack.getAsJsonObject("data")
                .getAsJsonObject("finalOutput")
                .get("icon")
                .isJsonNull());
        assertEquals(
            11,
            noPack.getAsJsonObject("data")
                .getAsJsonObject("finalOutput")
                .get("quantity")
                .getAsLong());
        assertEquals(previousNormalizations + 1, normalizations.get());
        ResourceDescriptor capturedDescription = AE2Controller.itemIdentities.describe(baseKey);
        assertNotNull(capturedDescription);
        assertEquals(baseKey, capturedDescription.baseKey());
    }

    private static IStackList stacks(IAEGenericStack... rows) {
        return new IStackList() {

            public long web$getAmount(IAEKey key) {
                return 0;
            }

            public Iterable<IAEGenericStack> web$stacks() {
                return Arrays.asList(rows);
            }
        };
    }

    @Test
    void historyIconsSurviveNativeAndRegistryRetirementWithMergedOutput() throws Exception {
        StableKey exact = TestGridFixtures.key(101);
        StableKey base = TestGridFixtures.key(102);
        installPack(exact, base);
        port = HttpServerTestFixture.startWorld(config);
        TestGrid grid = TestGridFixtures.grid(103);
        AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
        TestGridFixtures.track(grid);
        AtomicBoolean unavailable = new AtomicBoolean();
        AtomicReference<IAEGenericStack> output = new AtomicReference<>();
        AtomicInteger waiting = new AtomicInteger();
        Thread serverThread = Thread.currentThread();
        IAEGenericStack exactOutput = null;
        ICraftingCPUCluster cpu = (ICraftingCPUCluster) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> {
                assertFalse(unavailable.get(), "Native CPU accessed after history publication");
                return switch (method.getName()) {
                    case "web$getFinalOutput" -> output.get();
                    case "web$getName" -> "History CPU";
                    case "web$getWaitingFor" -> new IStackList() {

                        public long web$getAmount(IAEKey key) {
                            return waiting.get();
                        }

                        public Iterable<IAEGenericStack> web$stacks() {
                            return Collections.emptyList();
                        }
                    };
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                };
            });
        for (int index = 0; index < 3; index++) {
            final int variant = index;
            IAEKey key = (IAEKey) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IAEKey.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName()
                        .equals("equals")) return proxy == args[0];
                    assertFalse(unavailable.get(), "Native identity accessed after history publication");
                    assertSame(serverThread, Thread.currentThread(), "Native identity accessed from HTTP worker");
                    return switch (method.getName()) {
                        case "web$supportsStableIdentity" -> true;
                        case "web$getKey" -> variant == 0 ? exact : TestGridFixtures.key(104 + variant);
                        case "web$copyIdentity" -> proxy;
                        case "web$getResourceType" -> ResourceType.ITEM;
                        case "web$getRegistryNamespace" -> "example";
                        case "web$getComponentCount", "web$getDamage" -> 0;
                        case "web$getRegistryPath", "web$getDisplayName" -> "product-" + variant;
                        case "web$getBaseKey" -> {
                            if (variant == 2) throw new IllegalStateException("Unsupported base identity");
                            yield base;
                        }
                        default -> throw new AssertionError(method.getName());
                    };
                });
            output.set(new IAEGenericStack() {

                public @NotNull IAEKey web$what() {
                    assertFalse(unavailable.get());
                    return key;
                }

                public long web$amount() {
                    assertFalse(unavailable.get());
                    return 10 + variant;
                }
            });
            if (index == 0) exactOutput = output.get();
            if (index == 1) {
                // A merge replaces both the previous exact identity and its output data.
                AE2JobTracker.addJob(cpu, grid, true, CraftingRequester.automation());
            } else {
                AE2JobTracker.addJob(cpu, grid, false, CraftingRequester.automation());
            }
            waiting.set(10 + variant);
            AE2JobTracker.updateCraftingStatus(cpu, key);
            waiting.set(0);
            AE2JobTracker.updateCraftingStatus(cpu, key);
            if (index != 0) AE2JobTracker.completeCrafting(grid, cpu);
        }
        // Also retain an exact-match job independently of the merged fallback job.
        // Unowned identity entries may be reclaimed between jobs; history must not depend on their lifetime.
        AE2Controller.itemIdentities.clear();
        output.set(exactOutput);
        AE2JobTracker.addJob(cpu, grid, false, CraftingRequester.automation());
        AE2JobTracker.completeCrafting(grid, cpu);
        unavailable.set(true);
        AE2Controller.itemIdentities.clear();
        CraftingHistoryService history = CoreEngine.getCraftingHistory();
        assertNotNull(history);
        history.flush();
        String path = "/api/grids/" + CoreEngine.GRID_IDENTITIES.getKey(grid) + "/crafting-history";
        JsonObject response = json(connection(path + "?icons=true"));
        assertEquals(
            packId,
            response.getAsJsonObject("icons")
                .get("packId")
                .getAsString());
        assertEquals(
            1,
            response.getAsJsonObject("icons")
                .getAsJsonArray("pages")
                .size());
        assertEquals(
            3,
            response.getAsJsonArray("data")
                .size());
        for (JsonElement entry : response.getAsJsonArray("data")) {
            JsonObject row = entry.getAsJsonObject();
            JsonObject product = row.getAsJsonObject("finalOutput");
            assertFalse(row.has("icon"));
            assertFalse(product.has("baseKey"), "Detached icon identity is not part of the stack wire contract");
            assertFalse(
                product.get("itemKey")
                    .isJsonNull(),
                "Base failure preserves exact identity");
            boolean missing = product.get("registryPath")
                .getAsString()
                .equals("product-2");
            assertEquals(
                missing,
                product.get("icon")
                    .isJsonNull());
            if (product.get("registryPath")
                .getAsString()
                .equals("product-1")) {
                assertEquals(
                    11,
                    product.get("quantity")
                        .getAsLong());
            }
        }
        for (String suffix : new String[] { "", "?icons=false" }) {
            JsonObject plain = json(connection(path + suffix));
            assertTrue(
                plain.get("icons")
                    .isJsonNull());
            for (JsonElement entry : plain.getAsJsonArray("data")) {
                assertTrue(
                    entry.getAsJsonObject()
                        .getAsJsonObject("finalOutput")
                        .get("icon")
                        .isJsonNull());
            }
        }
        for (JsonElement entry : response.getAsJsonArray("data")) {
            int id = entry.getAsJsonObject()
                .get("id")
                .getAsInt();
            JsonObject detail = json(connection(path + "/" + id + "?icons=true"));
            JsonObject detailData = detail.getAsJsonObject("data");
            assertFalse(detailData.has("icon"));
            assertEquals(
                entry.getAsJsonObject()
                    .getAsJsonObject("finalOutput")
                    .get("icon"),
                detailData.getAsJsonObject("finalOutput")
                    .get("icon"));
            assertEquals(
                packId,
                detail.getAsJsonObject("icons")
                    .get("packId")
                    .getAsString());
            for (JsonElement resource : detailData.getAsJsonArray("items")) {
                JsonObject item = resource.getAsJsonObject();
                assertEquals(
                    item.get("registryPath")
                        .getAsString()
                        .equals("product-2"),
                    item.get("icon")
                        .isJsonNull());
                assertTrue(
                    item.get("craftedTotal")
                        .getAsLong() > 0);
            }
            for (String suffix : new String[] { "", "?icons=false" }) {
                JsonObject plain = json(connection(path + "/" + id + suffix));
                assertTrue(
                    plain.get("icons")
                        .isJsonNull());
                assertTrue(
                    plain.getAsJsonObject("data")
                        .getAsJsonObject("finalOutput")
                        .get("icon")
                        .isJsonNull());
                for (JsonElement resource : plain.getAsJsonObject("data")
                    .getAsJsonArray("items")) {
                    assertTrue(
                        resource.getAsJsonObject()
                            .get("icon")
                            .isJsonNull());
                }
            }
        }
    }

    @SuppressWarnings("BusyWait")
    private JsonObject syncedJson(String path) throws Exception {
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            Future<JsonObject> result = client.submit(() -> json(connection(path)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!result.isDone() && System.nanoTime() < deadline) {
                CoreEngine.onServerTick();
                Thread.sleep(1);
            }
            return result.get(1, TimeUnit.SECONDS);
        } finally {
            client.shutdownNow();
        }
    }

    private void installPack(StableKey... keys) throws Exception {
        Path generated;
        IconPack.Metadata metadata = new IconPack.Metadata(
            "1.7.10",
            "forge",
            "test-identity",
            "test",
            "today",
            Collections.emptyMap(),
            Collections.emptyList());
        try (IconPackWriter writer = new IconPackWriter(directory, metadata, 512)) {
            int[] pixels = new int[4096];
            Arrays.fill(pixels, 0xff405060);
            if (keys.length == 0) writer.add(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), pixels);
            for (StableKey key : keys) writer.add(key, pixels);
            generated = writer.finish();
        }
        try (IconPack pack = IconPack.open(generated, "1.7.10", "forge", "test-identity")) {
            packId = pack.packId();
            digest = pack.pages()
                .get(0)
                .digest();
            try (InputStream input = pack.openPage(digest)) {
                png = IOUtils.toByteArray(input);
            }
        }
        Files.move(generated, directory.resolve("ae2webintegration/icons.ae2wi-icons"));
    }

    private String pagePath() {
        return "/api/icon-packs/" + packId + "/pages/" + digest;
    }

    private HttpURLConnection connection(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(5000);
        return connection;
    }

    private JsonObject json(HttpURLConnection connection) throws Exception {
        assertEquals(200, connection.getResponseCode());
        assertEquals("no-store", connection.getHeaderField("Cache-Control"));
        try (InputStream body = connection.getInputStream()) {
            return new Gson().fromJson(IOUtils.toString(body, StandardCharsets.UTF_8), JsonObject.class);
        }
    }
}
