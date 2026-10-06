package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

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
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        config = new ConfigTestFixture(directory.toFile());
        config.write("general.port", port);
        config.write("general.allow_no_password_on_localhost", true);
        config.write("general.check_for_updates", false);
        Config.reload();
        AE2Controller.serverPlatform = new IServerPlatform() {

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

    @Test
    void installedPackPublishesAvailabilityAndAuthenticatedImmutablePages() throws Exception {
        installPack();
        CoreEngine.onServerStarted();
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
    void absentInvalidAndMismatchedPacksLeaveNormalServerAvailableAcrossRestarts() throws Exception {
        CoreEngine.onServerStarted();
        assertFalse(
            json(connection("/api/icon-pack")).getAsJsonObject("data")
                .get("available")
                .getAsBoolean());
        CoreEngine.onServerStopped();
        Path installed = directory.resolve("ae2webintegration/icons.ae2wi-icons");
        Files.write(installed, new byte[] { 1, 2, 3 });
        CoreEngine.onServerStarted();
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
        CoreEngine.onServerStarted();
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
        CoreEngine.onServerStarted();
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
        CoreEngine.onServerStarted();
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
        CoreEngine.onServerStarted();
        AtomicInteger normalized = new AtomicInteger();
        ItemIdentityRequestTest.Resource exact = iconResource("exact", 12, exactKey, baseKey, normalized);
        ItemIdentityRequestTest.Resource fallback = iconResource(
            "worn",
            7,
            TestGridFixtures.key(72),
            baseKey,
            normalized);
        ItemIdentityRequestTest.Resource missing = new ItemIdentityRequestTest.Resource("missing", 3, false);
        ItemIdentityRequestTest.Grid grid = new ItemIdentityRequestTest.Grid(31, exact, fallback, missing);
        AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
        String path = "/api/grids/" + TestGridFixtures.resolvedKey(grid) + "/items";
        JsonObject plain = syncedJson(path);
        assertTrue(
            plain.has("icons") && plain.get("icons")
                .isJsonNull());
        assertEquals(0, normalized.get());
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
        assertEquals(1, normalized.get(), "Exact matches never normalize; warm fallback reuses its retained memo");
        syncedJson(path + "?icons=false");
        assertEquals(1, normalized.get());
        HttpURLConnection invalid = connection(path + "?icons=perhaps");
        assertEquals(400, invalid.getResponseCode());
        invalid.disconnect();
    }

    private ItemIdentityRequestTest.Resource iconResource(String id, long quantity, StableKey exact, StableKey base,
        AtomicInteger calls) {
        return new ItemIdentityRequestTest.Resource(id, quantity, false) {

            public @NotNull IAEKey web$copyIdentity() {
                return this;
            }

            public @NotNull StableKey web$getKey() {
                return exact;
            }

            public StableKey web$getIconBaseKey() {
                calls.incrementAndGet();
                return base;
            }
        };
    }

    @Test
    void cpuUsesIndependentIdentityOwnershipAndPreservesExistingAggregation() throws Exception {
        StableKey baseKey = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        StableKey exactKey = TestGridFixtures.key(91);
        StableKey cpuKey = TestGridFixtures.key(92);
        installPack(baseKey);
        CoreEngine.onServerStarted();
        AtomicInteger normalizations = new AtomicInteger();
        AtomicBoolean busy = new AtomicBoolean(true);
        ItemIdentityRequestTest.Resource resource = iconResource("worn", 7, exactKey, baseKey, normalizations);
        IStackList resources = stacks(resource, resource);
        ICraftingCPUCluster cpu = (ICraftingCPUCluster) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "web$getKey" -> cpuKey;
                case "web$isBusy" -> busy.get();
                case "web$getName" -> "CPU";
                case "web$getAvailableStorage" -> 1024L;
                case "web$getUsedStorage", "web$getCoProcessors" -> 0L;
                case "web$getFinalOutput" -> resource;
                case "web$getActiveItems" -> 6L;
                case "web$getPendingItems" -> 2L;
                case "web$getStorageItems" -> 3L;
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
                default -> null;
            });
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(93, false, AEControllerState.CONTROLLER_ONLINE) {

            public IAECraftingGrid web$getCraftingGrid() {
                return crafting;
            }

            public IAEStorageGrid web$getStorageGrid() {
                return (IAEStorageGrid) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { IAEStorageGrid.class },
                    (proxy, method, args) -> stacks());
            }
        };
        AE2Controller.AE2Interface = new TestGridFixtures.TestAE(grid) {

            public IStackList web$createStackList() {
                return resources;
            }
        };
        String gridPath = "/api/grids/" + TestGridFixtures.resolvedKey(grid);
        String path = gridPath + "/cpus/" + cpuKey;
        JsonObject overview = syncedJson(gridPath + "/cpus");
        assertTrue(
            overview.get("icons")
                .isJsonNull());
        assertEquals(0, normalizations.get());
        JsonObject enabledOverview = syncedJson(gridPath + "/cpus?icons=true");
        JsonObject product = enabledOverview.getAsJsonObject("data")
            .getAsJsonObject(cpuKey.toString());
        assertNotNull(product.getAsJsonObject("icon"));
        assertEquals(
            7,
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
        assertEquals(1, normalizations.get());
        for (int poll = 0; poll < 2; poll++) {
            JsonObject response = syncedJson(path + "?icons=true");
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
            syncedJson(path + "?icons=false");
            syncedJson(gridPath + "/items?icons=true");
            System.gc();
        }
        assertEquals(1, normalizations.get());
        busy.set(false);
        JsonObject idle = syncedJson(gridPath + "/cpus?icons=true").getAsJsonObject("data")
            .getAsJsonObject(cpuKey.toString());
        assertTrue(
            idle.get("icon")
                .isJsonNull());
        assertTrue(
            idle.get("finalOutput")
                .isJsonNull());
        assertTrue(
            syncedJson(path + "?icons=true").getAsJsonObject("data")
                .get("items")
                .isJsonNull());
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
        CoreEngine.onServerStarted();
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(103);
        AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
        TestGridFixtures.track(grid);
        AtomicBoolean unavailable = new AtomicBoolean();
        AtomicInteger normalizations = new AtomicInteger();
        AtomicReference<IAEGenericStack> output = new AtomicReference<>();
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
                    assertFalse(unavailable.get(), "Native identity accessed after history publication");
                    assertSame(serverThread, Thread.currentThread(), "Native identity accessed from HTTP worker");
                    return switch (method.getName()) {
                        case "web$getKey" -> variant == 0 ? exact : TestGridFixtures.key(104 + variant);
                        case "web$copyIdentity" -> proxy;
                        case "web$getItemID", "web$getDisplayName" -> "product-" + variant;
                        case "web$getIconBaseKey" -> {
                            normalizations.incrementAndGet();
                            if (variant == 2) throw new IllegalStateException("Unsupported base identity");
                            yield base;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
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
                AE2JobTracker.addJob(cpu, grid, true);
            } else {
                AE2JobTracker.addJob(cpu, grid, false);
            }
            if (index != 0) AE2JobTracker.completeCrafting(grid, cpu);
        }
        // Also retain an exact-match job independently of the merged fallback job.
        output.set(exactOutput);
        AE2JobTracker.addJob(cpu, grid, false);
        AE2JobTracker.completeCrafting(grid, cpu);
        assertEquals(2, normalizations.get(), "Only missing exact icons normalize, once during capture");
        unavailable.set(true);
        AE2Controller.itemIdentities.clear();
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
        for (com.google.gson.JsonElement entry : response.getAsJsonArray("data")) {
            JsonObject row = entry.getAsJsonObject();
            JsonObject product = row.getAsJsonObject("finalOutput");
            assertEquals(
                4,
                product.entrySet()
                    .size(),
                "Detached icon identity is not part of the stack wire contract");
            assertFalse(
                product.get("itemKey")
                    .isJsonNull(),
                "Base failure preserves exact identity");
            boolean missing = product.get("itemId")
                .getAsString()
                .equals("product-2");
            assertEquals(
                missing,
                row.get("icon")
                    .isJsonNull());
            if (product.get("itemId")
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
            for (com.google.gson.JsonElement entry : plain.getAsJsonArray("data")) {
                assertTrue(
                    entry.getAsJsonObject()
                        .get("icon")
                        .isJsonNull());
            }
        }
        assertEquals(2, normalizations.get());
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
                .get(0).digest;
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
            return new Gson()
                .fromJson(IOUtils.toString(body, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
        }
    }
}
