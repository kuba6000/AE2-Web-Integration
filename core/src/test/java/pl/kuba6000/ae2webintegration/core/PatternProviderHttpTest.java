package pl.kuba6000.ae2webintegration.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.CONTROLLER_ONLINE;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProvider;

class PatternProviderHttpTest {

    @TempDir
    File root;
    private final ScheduledExecutorService game = Executors.newSingleThreadScheduledExecutor();
    private ConfigTestFixture config;
    private final IServerPlatform previousPlatform = AE2Controller.serverPlatform;
    private final IAE previous = AE2Controller.AE2Interface;
    private final TestPatternProvider provider = new TestPatternProvider();
    private final AtomicInteger reads = new AtomicInteger();
    private boolean present = true;
    private long revision;
    private volatile boolean supported = true;
    private final TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(420, false, CONTROLLER_ONLINE) {

        @Override
        public @NotNull Iterable<? extends IPatternProvider> web$getPatternProviders() {
            reads.incrementAndGet();
            return present ? Collections.singletonList(provider) : Collections.emptyList();
        }

        @Override
        public long web$getPatternRevision() {
            return revision;
        }
    };
    private String host;
    private String base;

    @BeforeEach
    void start() throws Exception {
        CraftingHistoryWorld.Platform platform = new CraftingHistoryWorld.Platform(root);
        config = new ConfigTestFixture(platform.getConfigDirectory());
        CoreEngine.init(platform);
        Config.INSTANCE.general.allowNoPasswordOnLocalhost = true;
        Config.INSTANCE.general.checkForUpdates = false;
        int port = HttpServerTestFixture.startWorld();
        StableKey key = game.submit(() -> {
            AE2Controller.AE2Interface = new TestGridFixtures.TestAE(grid) {

                @Override
                public @NotNull Map<ServerCapability, Boolean> web$getCapabilities() {
                    return Collections.singletonMap(ServerCapability.PATTERN_PROVIDERS, supported);
                }
            };
            return TestGridFixtures.resolvedKey(grid);
        })
            .get(3, SECONDS);
        host = "http://127.0.0.1:" + port;
        base = host + "/api/grids/" + key + "/pattern-providers";
        game.scheduleAtFixedRate(CoreEngine::onServerTick, 0, 20, MILLISECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        game.shutdownNow();
        assertTrue(game.awaitTermination(3, SECONDS));
        CoreEngine.onServerStopped();
        AE2Controller.AE2Interface = previous;
        AE2Controller.serverPlatform = previousPlatform;
        config.close();
    }

    @Test
    void snapshotAndStreamShareProviderKeysSlotIndicesAndResourceQuantities() throws Exception {
        HttpURLConnection request = connection(base + "?live=true");
        JsonObject snapshot;
        try {
            assertEquals(200, request.getResponseCode());
            try (InputStreamReader body = new InputStreamReader(request.getInputStream(), UTF_8)) {
                snapshot = new JsonParser().parse(body)
                    .getAsJsonObject();
            }
        } finally {
            request.disconnect();
        }
        assertEquals(
            "OK",
            snapshot.get("status")
                .getAsString());
        String generation = snapshot.getAsJsonObject("view")
            .get("generation")
            .getAsString();
        JsonObject row = snapshot.getAsJsonArray("data")
            .get(0)
            .getAsJsonObject();
        String key = row.get("key")
            .getAsString();
        assertEquals(
            "north",
            row.getAsJsonObject("location")
                .get("side")
                .getAsString());
        JsonArray slots = row.getAsJsonArray("slots");
        assertEquals(
            "empty",
            slots.get(0)
                .getAsJsonObject()
                .get("kind")
                .getAsString());
        assertEquals(
            "invalid",
            pattern(slots, 1).get("kind")
                .getAsString());
        assertEquals(
            0,
            pattern(slots, 1).getAsJsonArray("inputs")
                .size());
        assertEquals(
            0,
            pattern(slots, 1).getAsJsonArray("outputs")
                .size());
        assertEquals(
            "crafting",
            pattern(slots, 2).get("kind")
                .getAsString());
        assertEquals(
            "processing",
            pattern(slots, 3).get("kind")
                .getAsString());
        JsonObject output = pattern(slots, 3).getAsJsonArray("outputs")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            3L,
            output.get("quantity")
                .getAsLong());
        assertEquals(
            Objects.requireNonNull(provider.iron.web$getKey())
                .toString(),
            output.get("itemKey")
                .getAsString());
        assertEquals(
            4L,
            pattern(slots, 3).getAsJsonArray("inputs")
                .get(0)
                .getAsJsonObject()
                .get("quantity")
                .getAsLong());

        String events = base + "/events?subscription="
            + snapshot.get("subscription")
                .getAsString();
        HttpURLConnection stream = connection(events);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(stream.getInputStream(), UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            assertEquals(200, status(events, "POST", null));
            game.submit(() -> {
                provider.name = "New name";
                revision++;
            })
                .get(3, SECONDS);
            CpuFixtures.Event update = CpuFixtures.readEvent(input);
            assertEquals("delta", update.name());
            assertEquals(
                generation,
                update.data()
                    .getAsJsonObject("view")
                    .get("generation")
                    .getAsString());
            JsonObject replacement = update.data()
                .getAsJsonArray("providers")
                .get(0)
                .getAsJsonObject();
            assertEquals(
                "New name",
                replacement.get("name")
                    .getAsString());
            assertEquals(
                key,
                replacement.get("key")
                    .getAsString());
            assertEquals(slots, replacement.get("slots"));
            game.submit(() -> {
                present = false;
                revision++;
            })
                .get(3, SECONDS);
            assertEquals(
                key,
                CpuFixtures.readEvent(input)
                    .data()
                    .getAsJsonArray("removed")
                    .get(0)
                    .getAsString());
            game.submit(
                () -> Objects.requireNonNull(CoreEngine.getPatternProviders())
                    .tick(System.currentTimeMillis() + MINUTES.toMillis(3)))
                .get(3, SECONDS);
            assertEquals(400, status(events, "POST", null));
        } finally {
            stream.disconnect();
        }
    }

    @Test
    void headDoesNotScanOrDescribeTheWrongSnapshotLength() throws Exception {
        HttpURLConnection head = connection(base);
        head.setRequestMethod("HEAD");
        long length;
        try {
            assertEquals(200, head.getResponseCode());
            assertEquals(
                -1,
                head.getInputStream()
                    .read());
            length = head.getContentLengthLong();
        } finally {
            head.disconnect();
        }
        assertEquals(0, reads.get());
        if (length >= 0) {
            HttpURLConnection get = connection(base);
            try {
                assertEquals(200, get.getResponseCode());
                assertEquals(get.getContentLengthLong(), length);
            } finally {
                get.disconnect();
            }
        }
    }

    @Test
    void aDeniedSnapshotDoesNotReadProviderInventories() throws Exception {
        String live = base + "?live=true";
        assertEquals(
            404,
            status(host + "/api/grids/" + StableKey.random() + "/pattern-providers?live=true", "GET", null));
        supported = false;
        assertEquals(400, status(live, "GET", null));
        Config.INSTANCE.general.publicMode = true;
        Config.INSTANCE.general.allowNoPasswordOnLocalhost = false;
        assertEquals(401, status(live, "GET", null));
        String nonOwner = loginAsNonOwner();
        assertEquals(
            403,
            status(live, "GET", nonOwner),
            "Grid permissions are checked before revealing adapter capabilities");
        supported = true;
        assertEquals(403, status(live, "GET", nonOwner));
        assertEquals(0, reads.get());
    }

    private String loginAsNonOwner() throws Exception {
        CoreData.setPassword(
            TestGridFixtures.playerIdentity(8),
            PasswordHelper.generateStrongPasswordHash("provider-test"));
        HttpURLConnection login = connection(host + "/api/auth/login");
        login.setRequestMethod("POST");
        login.setRequestProperty("Content-Type", "application/json");
        login.setRequestProperty("X-AE2-Request", "true");
        login.setDoOutput(true);
        try {
            try (OutputStream body = login.getOutputStream()) {
                body.write("{\"username\":\"Player8\",\"password\":\"provider-test\"}".getBytes(UTF_8));
            }
            try (InputStreamReader input = new InputStreamReader(login.getInputStream(), UTF_8)) {
                return "Bearer " + new JsonParser().parse(input)
                    .getAsJsonObject()
                    .getAsJsonObject("data")
                    .get("token")
                    .getAsString();
            }
        } finally {
            login.disconnect();
        }
    }

    private static int status(String url, String method, String authorization) throws Exception {
        HttpURLConnection request = connection(url);
        try {
            request.setRequestMethod(method);
            if (method.equals("POST")) request.setRequestProperty("X-AE2-Request", "true");
            if (authorization != null) request.setRequestProperty("Authorization", authorization);
            return request.getResponseCode();
        } finally {
            request.disconnect();
        }
    }

    private static JsonObject pattern(JsonArray slots, int index) {
        JsonObject slot = slots.get(index)
            .getAsJsonObject();
        assertEquals(
            "pattern",
            slot.get("kind")
                .getAsString());
        return slot.getAsJsonObject("pattern");
    }

    private static HttpURLConnection connection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url)
            .toURL()
            .openConnection();
        connection.setReadTimeout(5000);
        return connection;
    }

}
