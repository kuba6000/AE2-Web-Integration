package pl.kuba6000.ae2webintegration.core;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.TestGridFixtures.OWNER_ID;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders.DeleteStandingOrder;
import pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders.GetStandingOrders;
import pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders.PatchStandingOrder;
import pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders.PostStandingOrder;
import pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders.RunStandingOrder;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.standingorders.AmountPolicy;
import pl.kuba6000.ae2webintegration.core.standingorders.Condition;
import pl.kuba6000.ae2webintegration.core.standingorders.CpuTarget;
import pl.kuba6000.ae2webintegration.core.standingorders.CraftAction;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderDefinition;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEngine;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;

/**
 * The standing order endpoints against a started world: the stored orders, the engine and the authorization,
 * validation and rate limits in front of them.
 */
@SuppressWarnings("PMD.AvoidMagicNumbers") // Arbitrary fixture quantities and seeds.
class StandingOrderHttpTest {

    private static final String JSON = "Content-Type: application/json\r\n";
    private static final String OWNER = "Authorization: Bearer owner\r\n" + JSON;
    private static final String STRANGER = "Authorization: Bearer stranger\r\n" + JSON;
    private static final String ADMIN = "Authorization: Bearer admin\r\n" + JSON;

    @TempDir
    File configRoot;
    private final AtomicBoolean lightModeSupported = new AtomicBoolean();
    private IAE previousAe;
    private HttpServer server;
    private ItemIdentityRequestTest.Grid grid;
    private ItemIdentityRequestTest.Resource plate;
    private ItemIdentityRequestTest.Resource ingot;
    private ItemIdentityRequestTest.Resource dust;
    private StableKey gridKey;
    private String orders;

    @BeforeEach
    void start() throws Exception {
        previousAe = AE2Controller.AE2Interface;
        CoreEngine.init(new Platform(configRoot));
        Config.INSTANCE.general.port = 0;
        Config.INSTANCE.general.checkForUpdates = false;
        CoreEngine.onServerStarted();
        plate = new ItemIdentityRequestTest.Resource("so-http-plate", 500, true);
        ingot = new ItemIdentityRequestTest.Resource("so-http-ingot", 500, true);
        dust = new ItemIdentityRequestTest.Resource("so-http-dust", 500, true);
        grid = new ItemIdentityRequestTest.Grid(771301, plate, ingot, dust);
        gridKey = TestGridFixtures.resolvedKey(grid);
        AE2Controller.AE2Interface = new TestGridFixtures.TestAE(grid) {

            @Override
            public Map<ServerCapability, Boolean> web$getCapabilities() {
                Map<ServerCapability, Boolean> capabilities = super.web$getCapabilities();
                capabilities.put(ServerCapability.CRAFTING_LIGHT_MODE, lightModeSupported.get());
                return capabilities;
            }
        };
        orders = "/api/grids/" + gridKey + "/standing-orders";
        describeResources(plate, ingot);
        ApiRouter router = new ApiRouter(exchange -> {
            String token = exchange.getRequestHeaders()
                .getFirst("Authorization");
            if (token == null) {
                token = exchange.getRequestHeaders()
                    .getFirst("Cookie");
                token = token == null ? null : token.replace("authenticationToken=", "Bearer ");
            }
            if ("Bearer owner".equals(token))
                return new AE2Controller.RequestContext(exchange, TestGridFixtures.principal(OWNER_ID));
            if ("Bearer stranger".equals(token))
                return new AE2Controller.RequestContext(exchange, TestGridFixtures.principal(42));
            if ("Bearer admin".equals(token)) return new AE2Controller.RequestContext(exchange, WebPrincipal.admin());
            return null;
        }, exchange -> false, request -> fail("Standing orders never dispatch game-thread work"));
        router.register(GetStandingOrders.class);
        router.register(PostStandingOrder.class);
        router.register(PatchStandingOrder.class);
        router.register(DeleteStandingOrder.class);
        router.register(RunStandingOrder.class);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api", router);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        CoreEngine.onServerStopped();
        AE2Controller.AE2Interface = previousAe;
    }

    /** The terminal of an open storage view is what describes resources that were never stored. */
    private void describeResources(ItemIdentityRequestTest.Resource... resources) {
        TerminalRegistry terminals = CoreEngine.getTerminalRegistry();
        assertNotNull(terminals);
        long now = System.currentTimeMillis();
        terminals.activate(gridKey, 1, now);
        java.util.List<StorageObservation.Row> rows = new java.util.ArrayList<>();
        Map<StableKey, ResourceDescriptor> descriptions = new java.util.HashMap<>();
        for (ItemIdentityRequestTest.Resource resource : resources) {
            StableKey key = resource.web$getKey();
            rows.add(new StorageObservation.Row(key, resource.quantity, true));
            descriptions.put(
                key,
                new ResourceDescriptor("example", resource.id, 0, 0, resource.id, ResourceType.ITEM, key, key));
        }
        terminals.observed(
            gridKey,
            1,
            new StorageObservation(
                now,
                true,
                true,
                rows,
                Collections.emptyList(),
                descriptions,
                Collections.emptyList()));
    }

    private static String key(ItemIdentityRequestTest.Resource resource) {
        return resource.web$getKey()
            .toString();
    }

    private String platesBelow(String name) {
        return "{\"name\":\"" + name
            + "\",\"enabled\":true,\"cooldownSeconds\":30,\"trigger\":{\"type\":\"all\",\"children\":[{\"type\":\"stockBelow\",\"resource\":\""
            + key(plate)
            + "\",\"amount\":100},{\"type\":\"not\",\"child\":{\"type\":\"idleCpusAtLeast\",\"count\":2}}]},"
            + "\"action\":{\"resource\":\""
            + key(plate)
            + "\",\"amount\":{\"type\":\"fillTo\",\"target\":1000,\"maxBatch\":500},"
            + "\"options\":{\"lightMode\":false,\"ignoreMissing\":false},\"cpu\":{\"type\":\"any\"}}}";
    }

    private JsonObject definition(String json, Consumer<JsonObject> change) {
        JsonObject definition = new JsonParser().parse(json)
            .getAsJsonObject();
        change.accept(definition);
        return definition;
    }

    private JsonObject platesBelow(Consumer<JsonObject> change) {
        return definition(platesBelow("Keep plates"), change);
    }

    private static JsonObject object(JsonObject root, String... path) {
        JsonObject current = root;
        for (String member : path) {
            current = member.matches("\\d+") ? null : current.getAsJsonObject(member);
        }
        return current;
    }

    private static JsonObject trigger(JsonObject definition) {
        return definition.getAsJsonObject("trigger");
    }

    private static JsonObject child(JsonObject group, int index) {
        return group.getAsJsonArray("children")
            .get(index)
            .getAsJsonObject();
    }

    private static JsonElement withoutNulls(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> member : value.getAsJsonObject()
                .entrySet()) {
                if (!member.getValue()
                    .isJsonNull()) result.add(member.getKey(), withoutNulls(member.getValue()));
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) result.add(withoutNulls(element));
            return result;
        }
        return value;
    }

    private Reply post(String headers, JsonObject body) throws Exception {
        return request("POST", orders, headers, body.toString());
    }

    private JsonObject data(Reply reply) {
        assertEquals(HTTP_OK, reply.status(), reply.body());
        return reply.json()
            .getAsJsonObject("data");
    }

    private JsonObject list(String headers) throws Exception {
        return data(request("GET", orders, headers, ""));
    }

    private static String orderKey(JsonObject listing) {
        return listing.getAsJsonArray("orders")
            .get(0)
            .getAsJsonObject()
            .get("orderKey")
            .getAsString();
    }

    private StandingOrderStore.Valid seed(String name, boolean enabled, StableKey resource) throws Exception {
        StandingOrderStore store = CoreEngine.getStandingOrderStore();
        StandingOrderEngine engine = CoreEngine.getStandingOrders();
        assertNotNull(store);
        assertNotNull(engine);
        StandingOrderStore.Valid order = store.create(
            gridKey,
            WebPrincipal.admin(),
            new StandingOrderDefinition(
                name,
                enabled,
                new Condition.StockBelow(resource, 100),
                new CraftAction(
                    resource,
                    new AmountPolicy.Fixed(64),
                    new pl.kuba6000.ae2webintegration.core.api.CraftingOptions(false, false),
                    CpuTarget.any()),
                0),
            Collections.singletonMap(
                resource,
                new ResourceDescriptor("example", "seeded", 0, 0, "Seeded", ResourceType.ITEM, resource, resource)));
        engine.post(gridKey, order);
        return order;
    }

    private void tick() {
        for (int index = 0; index < 3; index++) CoreEngine.onServerTick();
    }

    /** Ticks until the network was read for the first time, which the coordinator does within about a second. */
    private void settle() throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (grid.storageReads == 0 && System.nanoTime() < deadline) {
            CoreEngine.onServerTick();
            Thread.sleep(50);
        }
        assertTrue(grid.storageReads > 0, "The engine read the network");
        tick();
    }

    private static String status(JsonObject listing) {
        JsonElement status = listing.getAsJsonArray("orders")
            .get(0)
            .getAsJsonObject()
            .get("status");
        return status.isJsonNull() ? null
            : status.getAsJsonObject()
                .get("status")
                .getAsString();
    }

    private void assertRejected(Reply reply, int httpStatus, String status, String reason, String path) {
        assertEquals(httpStatus, reply.status(), reply.body());
        JsonObject json = reply.json();
        assertEquals(
            status,
            json.get("status")
                .getAsString());
        if (reason == null) {
            assertTrue(
                json.get("data")
                    .isJsonNull());
        } else {
            JsonObject data = json.getAsJsonObject("data");
            assertEquals(
                reason,
                data.get("reason")
                    .getAsString());
            assertEquals(
                path,
                data.get("path")
                    .getAsString());
        }
    }

    // ----- Listing and the created order -----

    @Test
    void aNewNetworkListsNoOrdersAndTellsWhatTheAccountMayDo() throws Exception {
        JsonObject listing = list(OWNER);
        assertTrue(
            listing.get("serverEnabled")
                .getAsBoolean());
        assertTrue(
            listing.get("gridEnabled")
                .getAsBoolean());
        assertTrue(
            listing.get("canEdit")
                .getAsBoolean());
        assertEquals(
            128,
            listing.get("maxOrders")
                .getAsInt());
        assertEquals(
            0,
            listing.getAsJsonArray("orders")
                .size());
        assertTrue(
            listing.getAsJsonObject("resources")
                .entrySet()
                .isEmpty());
        assertTrue(
            request("GET", orders, OWNER, "").json()
                .get("icons")
                .isJsonNull());
    }

    @Test
    void aCreatedOrderIsListedWithItsDefinitionAndTheDescriptionsOfItsResources() throws Exception {
        JsonObject submitted = platesBelow(definition -> {});
        JsonObject listing = data(post(OWNER, submitted));
        assertEquals(
            1,
            listing.getAsJsonArray("orders")
                .size());
        JsonObject order = listing.getAsJsonArray("orders")
            .get(0)
            .getAsJsonObject();
        assertEquals(submitted, withoutNulls(order.get("definition")));
        assertTrue(
            order.get("invalid")
                .isJsonNull());
        assertEquals(
            Collections.singleton(key(plate)),
            listing.getAsJsonObject("resources")
                .keySet());
        JsonObject description = listing.getAsJsonObject("resources")
            .getAsJsonObject(key(plate));
        assertEquals(
            "so-http-plate",
            description.get("displayName")
                .getAsString());
        assertEquals(
            "ITEM",
            description.get("resourceType")
                .getAsString());
        assertEquals(list(OWNER), listing, "A later read returns what the mutation returned");
        assertEquals(
            list(OWNER).getAsJsonArray("orders")
                .get(0),
            order);
    }

    @Test
    void everyConditionAndAmountKindRoundTripsThroughCreateListAndReplace() throws Exception {
        JsonObject all = platesBelow(definition -> {
            JsonObject trigger = new JsonParser().parse(
                "{\"type\":\"any\",\"children\":[{\"type\":\"stockAtLeast\",\"resource\":\"" + key(ingot)
                    + "\",\"amount\":9223372036854775807},{\"type\":\"all\",\"children\":[{\"type\":\"idleCpusAtLeast\",\"count\":1024},"
                    + "{\"type\":\"not\",\"child\":{\"type\":\"stockBelow\",\"resource\":\""
                    + key(plate)
                    + "\",\"amount\":5}}]}]}")
                .getAsJsonObject();
            definition.add("trigger", trigger);
            object(definition, "action").add(
                "amount",
                new JsonParser().parse("{\"type\":\"fixed\",\"batch\":64}")
                    .getAsJsonObject());
            object(definition, "action").add(
                "cpu",
                new JsonParser().parse("{\"type\":\"specific\",\"key\":\"" + TestGridFixtures.key(77) + "\"}")
                    .getAsJsonObject());
            definition.addProperty("name", "Zlecenie stałe é中");
            definition.addProperty("cooldownSeconds", 604800);
        });
        JsonObject listing = data(post(OWNER, all));
        assertEquals(
            all,
            withoutNulls(
                listing.getAsJsonArray("orders")
                    .get(0)
                    .getAsJsonObject()
                    .get("definition")));
        assertEquals(
            new java.util.HashSet<>(Arrays.asList(key(plate), key(ingot))),
            listing.getAsJsonObject("resources")
                .keySet());
        JsonObject changed = platesBelow(definition -> definition.addProperty("name", "Renamed"));
        JsonObject replaced = data(request("PATCH", orders + "/" + orderKey(listing), OWNER, changed.toString()));
        assertEquals(
            changed,
            withoutNulls(
                replaced.getAsJsonArray("orders")
                    .get(0)
                    .getAsJsonObject()
                    .get("definition")));
        assertEquals(
            Collections.singleton(key(plate)),
            replaced.getAsJsonObject("resources")
                .keySet());
    }

    @Test
    void theStatusIsNullUntilTheEngineEvaluatedTheOrderAndThenCarriesItsRuntimeState() throws Exception {
        JsonObject listing = data(post(OWNER, platesBelow(definition -> {})));
        assertNull(status(listing), "Pending until the engine's next tick");
        settle();
        JsonObject evaluated = list(OWNER);
        JsonObject status = evaluated.getAsJsonArray("orders")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("status");
        assertEquals(
            "IDLE",
            status.get("status")
                .getAsString(),
            "Stock is above the trigger");
        assertTrue(
            status.has("message") && status.has("changedAt")
                && status.has("lastSubmittedAmount")
                && status.has("nextCheckAt")
                && status.has("craftingSince"));
        assertTrue(
            status.get("changedAt")
                .getAsLong() > 0);
    }

    @Test
    void anUnreadableStoredOrderIsListedWithItsReasonAndCanBeRepairedByReplacing() throws Exception {
        JsonObject created = data(post(OWNER, platesBelow(definition -> {})));
        String key = orderKey(created);
        CoreEngine.onServerStopping();
        CoreEngine.onServerStopped();
        Path database = new File(new File(configRoot, "test-save"), "ae2webintegration/grids.sqlite").toPath();
        try (WorldDatabase world = new WorldDatabase(database, Config.INSTANCE.resourceHistory)) {
            world.open();
            world.write(db -> {
                try (Statement sql = db.createStatement()) {
                    sql.execute("UPDATE standing_orders SET definition_version = 99 WHERE order_key = '" + key + "'");
                }
                return null;
            });
        }
        CoreEngine.onServerStarted();
        assertEquals(gridKey, TestGridFixtures.resolvedKey(grid));
        tick();
        JsonObject listing = list(OWNER);
        JsonObject order = listing.getAsJsonArray("orders")
            .get(0)
            .getAsJsonObject();
        assertTrue(
            order.get("definition")
                .isJsonNull());
        assertEquals(
            "UNSUPPORTED_VERSION",
            order.getAsJsonObject("invalid")
                .get("reason")
                .getAsString());
        assertEquals(
            "",
            order.getAsJsonObject("invalid")
                .get("path")
                .getAsString());
        assertEquals("UNAVAILABLE", status(listing));
        for (String partial : new String[] { "{\"enabled\":false}", "{}",
            "{\"name\":\"Repaired\",\"enabled\":true}" }) {
            JsonObject refused = request("PATCH", orders + "/" + key, OWNER, partial).json();
            assertEquals(
                "INVALID_STANDING_ORDER",
                refused.get("status")
                    .getAsString());
            assertEquals(
                "MISSING_FIELD",
                refused.getAsJsonObject("data")
                    .get("reason")
                    .getAsString());
        }
        assertRejected(
            request("PATCH", orders + "/" + key, OWNER, "{\"enabled\":false}"),
            HTTP_BAD_REQUEST,
            "INVALID_STANDING_ORDER",
            "MISSING_FIELD",
            "name");
        JsonObject repaired = data(
            request("PATCH", orders + "/" + key, OWNER, platesBelow(definition -> {}).toString()));
        assertTrue(
            repaired.getAsJsonArray("orders")
                .get(0)
                .getAsJsonObject()
                .get("invalid")
                .isJsonNull());
    }

    // ----- Replace and delete -----

    @Test
    void replacingTheDefinitionRenamesAndDisablesTheOrderInTheStoreAndTheEngine() throws Exception {
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        settle();
        assertEquals("IDLE", status(list(OWNER)));
        JsonObject listing = data(request("PATCH", orders + "/" + seeded.key(), OWNER, platesBelow(definition -> {
            definition.addProperty("name", "Renamed");
            definition.addProperty("enabled", false);
        }).toString()));
        JsonObject order = listing.getAsJsonArray("orders")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "Renamed",
            order.getAsJsonObject("definition")
                .get("name")
                .getAsString());
        assertFalse(
            order.getAsJsonObject("definition")
                .get("enabled")
                .getAsBoolean());
        tick();
        assertEquals("DISABLED", status(list(OWNER)));
        StandingOrderStore.Valid stored = (StandingOrderStore.Valid) CoreEngine.getStandingOrderStore()
            .get(gridKey, seeded.key());
        assertEquals(
            "Renamed",
            stored.definition()
                .name());
        assertEquals(seeded.key(), stored.key());
        assertEquals(seeded.ordinal(), stored.ordinal());
    }

    private static JsonObject copy(JsonObject value) {
        return new JsonParser().parse(value.toString())
            .getAsJsonObject();
    }

    private JsonObject patched(String orderKey, String changes) throws Exception {
        return data(request("PATCH", orders + "/" + orderKey, OWNER, changes)).getAsJsonArray("orders")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("definition");
    }

    @Test
    void anOmittedMemberKeepsItsStoredValueSoTogglingAndRenamingNeedNoDefinition() throws Exception {
        JsonObject original = platesBelow(definition -> {});
        String key = orderKey(data(post(OWNER, original)));
        JsonObject disabled = copy(original);
        disabled.addProperty("enabled", false);
        assertEquals(disabled, withoutNulls(patched(key, "{\"enabled\":false}")));
        JsonObject renamed = copy(disabled);
        renamed.addProperty("name", "Renamed");
        assertEquals(renamed, withoutNulls(patched(key, "{\"name\":\"Renamed\"}")));
        JsonObject slower = copy(renamed);
        slower.addProperty("cooldownSeconds", 90);
        assertEquals(slower, withoutNulls(patched(key, "{\"cooldownSeconds\":90}")));
        assertEquals(slower, withoutNulls(patched(key, "{}")), "An empty change keeps everything");
        tick();
        assertEquals("DISABLED", status(list(OWNER)), "The engine got the merged definition");
        StandingOrderStore.Valid stored = (StandingOrderStore.Valid) CoreEngine.getStandingOrderStore()
            .get(gridKey, StableKey.parse(key));
        assertEquals(
            "Renamed",
            stored.definition()
                .name());
        assertFalse(
            stored.definition()
                .enabled());
    }

    @Test
    void aPresentTriggerOrActionReplacesThatWholePartAndKeepsTheOther() throws Exception {
        JsonObject original = platesBelow(definition -> {});
        String key = orderKey(data(post(OWNER, original)));
        String trigger = "{\"type\":\"idleCpusAtLeast\",\"count\":1}";
        JsonObject newTrigger = copy(original);
        newTrigger.add("trigger", new JsonParser().parse(trigger));
        assertEquals(newTrigger, withoutNulls(patched(key, "{\"trigger\":" + trigger + "}")));
        String action = "{\"resource\":\"" + key(plate)
            + "\",\"amount\":{\"type\":\"fixed\",\"batch\":8},\"options\":{\"lightMode\":false,\"ignoreMissing\":false},"
            + "\"cpu\":{\"type\":\"any\"}}";
        JsonObject newAction = copy(newTrigger);
        newAction.add("action", new JsonParser().parse(action));
        assertEquals(newAction, withoutNulls(patched(key, "{\"action\":" + action + "}")));
    }

    @Test
    void theMergedDefinitionIsValidatedLikeANewOneAndABadChangeLeavesTheOrderAlone() throws Exception {
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        String order = orders + "/" + seeded.key();
        assertRejected(
            request("PATCH", order, OWNER, "{\"cooldownSeconds\":604801}"),
            HTTP_BAD_REQUEST,
            "INVALID_STANDING_ORDER",
            "COOLDOWN_OUT_OF_RANGE",
            "cooldownSeconds");
        assertRejected(
            request("PATCH", order, OWNER, "{\"name\":\"\"}"),
            HTTP_BAD_REQUEST,
            "INVALID_STANDING_ORDER",
            "NAME_LENGTH",
            "name");
        assertRejected(
            request(
                "PATCH",
                order,
                OWNER,
                "{\"trigger\":{\"type\":\"stockBelow\",\"resource\":\"" + key(plate) + "\"}}"),
            HTTP_BAD_REQUEST,
            "INVALID_STANDING_ORDER",
            "MISSING_FIELD",
            "trigger.amount");
        String light = "{\"action\":{\"resource\":\"" + key(plate)
            + "\",\"amount\":{\"type\":\"fixed\",\"batch\":8},\"options\":{\"lightMode\":true,\"ignoreMissing\":false},"
            + "\"cpu\":{\"type\":\"any\"}}}";
        assertRejected(
            request("PATCH", order, OWNER, light),
            HTTP_BAD_REQUEST,
            "INVALID_STANDING_ORDER",
            "CAPABILITY_UNAVAILABLE",
            "action.options.lightMode");
        for (String malformed : new String[] { "", "[]", "{\"enabled\":null}", "{\"enabled\":\"no\"}",
            "{\"unknown\":1}", "{\"trigger\":{}}", "{\"name\":\"A\",\"name\":\"B\"}" }) {
            assertRejected(request("PATCH", order, OWNER, malformed), HTTP_BAD_REQUEST, "BAD_PARAM", null, null);
        }
        assertEquals(
            seeded,
            CoreEngine.getStandingOrderStore()
                .get(gridKey, seeded.key()));
        assertRejected(
            request("PATCH", orders + "/" + TestGridFixtures.key(99), OWNER, "{\"enabled\":false}"),
            HTTP_NOT_FOUND,
            "STANDING_ORDER_NOT_FOUND",
            null,
            null);
        assertRejected(
            request("PATCH", orders + "/bad", OWNER, "{\"enabled\":false}"),
            HTTP_BAD_REQUEST,
            "BAD_PARAM",
            null,
            null);
    }

    @Test
    void anOrderStoredWithAnOptionTheServerNoLongerSupportsCanStillBeRenamedAndSwitchedOff() throws Exception {
        StandingOrderStore.Valid stored = CoreEngine.getStandingOrderStore()
            .create(
                gridKey,
                WebPrincipal.admin(),
                new StandingOrderDefinition(
                    "Lite",
                    true,
                    new Condition.StockBelow(plate.web$getKey(), 100),
                    new CraftAction(
                        plate.web$getKey(),
                        new AmountPolicy.Fixed(64),
                        new pl.kuba6000.ae2webintegration.core.api.CraftingOptions(true, false),
                        CpuTarget.any()),
                    0),
                Collections.singletonMap(
                    plate.web$getKey(),
                    new ResourceDescriptor(
                        "example",
                        "lite",
                        0,
                        0,
                        "Lite",
                        ResourceType.ITEM,
                        plate.web$getKey(),
                        plate.web$getKey())));
        CoreEngine.getStandingOrders()
            .post(gridKey, stored);
        String order = orders + "/" + stored.key();
        assertEquals(HTTP_OK, request("PATCH", order, OWNER, "{\"name\":\"Renamed\"}").status());
        assertEquals(HTTP_OK, request("PATCH", order, OWNER, "{\"cooldownSeconds\":10}").status());
        assertEquals(HTTP_OK, request("PATCH", order, OWNER, "{\"enabled\":false}").status());
        String keeping = "{\"action\":{\"resource\":\"" + key(plate)
            + "\",\"amount\":{\"type\":\"fixed\",\"batch\":8},\"options\":{\"lightMode\":true,\"ignoreMissing\":false},"
            + "\"cpu\":{\"type\":\"any\"}}}";
        assertRejected(
            request("PATCH", order, OWNER, keeping),
            HTTP_BAD_REQUEST,
            "INVALID_STANDING_ORDER",
            "CAPABILITY_UNAVAILABLE",
            "action.options.lightMode");
        assertEquals(
            HTTP_OK,
            request("PATCH", order, OWNER, keeping.replace("\"lightMode\":true", "\"lightMode\":false")).status());
    }

    @Test
    void anOrderKeyOfAnotherNetworkIsNotFoundAndNothingChanges() throws Exception {
        ItemIdentityRequestTest.Grid other = new ItemIdentityRequestTest.Grid(771302, plate);
        String otherOrders = "/api/grids/" + TestGridFixtures.resolvedKey(other) + "/standing-orders";
        StandingOrderStore.Valid mine = seed("Mine", true, plate.web$getKey());
        tick();
        String foreign = otherOrders + "/" + mine.key();
        for (String[] attempt : new String[][] { { "PATCH", foreign, "{\"enabled\":false}" }, { "DELETE", foreign, "" },
            { "POST", foreign + "/run", "" } }) {
            assertRejected(
                request(attempt[0], attempt[1], OWNER, attempt[2]),
                HTTP_NOT_FOUND,
                "STANDING_ORDER_NOT_FOUND",
                null,
                null);
        }
        assertEquals(
            0,
            data(request("GET", otherOrders, OWNER, "")).getAsJsonArray("orders")
                .size());
        assertEquals(
            mine,
            CoreEngine.getStandingOrderStore()
                .get(gridKey, mine.key()));
        assertNotNull(
            CoreEngine.getStandingOrders()
                .snapshot(mine.key()));
        assertEquals(
            1,
            list(OWNER).getAsJsonArray("orders")
                .size());
    }

    @Test
    void anOrderOfANetworkWithStandingOrdersSwitchedOffIsNotRunnableAndKeepsTheAllowance() throws Exception {
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        String run = orders + "/" + seeded.key() + "/run";
        GridSwitch.set(gridKey, false);
        tick();
        assertRejected(request("POST", run, OWNER, ""), HTTP_CONFLICT, "STANDING_ORDER_NOT_RUNNABLE", null, null);
        GridSwitch.set(gridKey, true);
        tick();
        assertEquals(HTTP_ACCEPTED, request("POST", run, OWNER, "").status());
    }

    @Test
    void theReadCostOfAChangeIsJudgedOnTheMergedDefinition() throws Exception {
        StandingOrderStore.Valid seeded = seed("Off", false, plate.web$getKey());
        String order = orders + "/" + seeded.key();
        assertEquals(HTTP_OK, request("PATCH", order, OWNER, "{\"name\":\"Renamed\"}").status());
        assertEquals(HTTP_OK, request("PATCH", order, OWNER, "{\"enabled\":true}").status());
        assertEquals(HTTP_OK, request("PATCH", order, OWNER, "{\"enabled\":false}").status());
        assertRejected(request("PATCH", order, OWNER, "{\"enabled\":true}"), 429, "TOO_MANY_REQUESTS", null, null);
    }

    @Test
    void deletingRemovesTheOrderFromTheStoreAndTheEngine() throws Exception {
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        StandingOrderStore.Valid other = seed("Other", true, ingot.web$getKey());
        tick();
        StandingOrderEngine engine = CoreEngine.getStandingOrders();
        assertNotNull(engine.snapshot(seeded.key()));
        JsonObject listing = data(request("DELETE", orders + "/" + seeded.key(), OWNER, ""));
        assertEquals(
            1,
            listing.getAsJsonArray("orders")
                .size());
        assertEquals(
            other.key()
                .toString(),
            orderKey(listing));
        assertNull(
            CoreEngine.getStandingOrderStore()
                .get(gridKey, seeded.key()));
        tick();
        assertNull(engine.snapshot(seeded.key()), "The engine forgot the deleted order");
        assertNotNull(engine.snapshot(other.key()));
        assertRejected(
            request("DELETE", orders + "/" + seeded.key(), OWNER, ""),
            HTTP_NOT_FOUND,
            "STANDING_ORDER_NOT_FOUND",
            null,
            null);
    }

    // ----- Run now -----

    @Test
    void runningAnOrderQueuesOneMoreReadOfTheNetwork() throws Exception {
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        settle();
        int reads = grid.storageReads;
        assertEquals("IDLE", status(list(OWNER)));
        Reply queued = request("POST", orders + "/" + seeded.key() + "/run", OWNER, "");
        assertEquals(HTTP_ACCEPTED, queued.status());
        assertEquals(
            "OK",
            queued.json()
                .get("status")
                .getAsString());
        assertTrue(
            queued.json()
                .get("data")
                .isJsonNull());
        assertEquals(reads, grid.storageReads, "HTTP only queues the request");
        tick();
        assertEquals(reads + 1, grid.storageReads);
    }

    @Test
    void anOrderThatCannotRunIsNotQueued() throws Exception {
        StandingOrderStore.Valid disabled = seed("Off", false, plate.web$getKey());
        assertRejected(
            request("POST", orders + "/" + disabled.key() + "/run", OWNER, ""),
            HTTP_CONFLICT,
            "STANDING_ORDER_NOT_RUNNABLE",
            null,
            null);
        assertRejected(
            request("POST", orders + "/" + TestGridFixtures.key(99) + "/run", OWNER, ""),
            HTTP_NOT_FOUND,
            "STANDING_ORDER_NOT_FOUND",
            null,
            null);
    }

    // ----- Validation -----

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = { "emptyName|NAME_LENGTH|name", "paddedName|NAME_NOT_TRIMMED|name",
            "controlName|NAME_INVALID_CHARACTER|name", "longCooldown|COOLDOWN_OUT_OF_RANGE|cooldownSeconds",
            "zeroAmount|AMOUNT_NOT_POSITIVE|trigger.children[0].amount",
            "unknownKind|UNKNOWN_KIND|trigger.children[0].type",
            "missingAmount|MISSING_FIELD|trigger.children[0].amount",
            "strayResource|UNKNOWN_FIELD|trigger.children[1].child.resource", "emptyGroup|EMPTY_GROUP|trigger.children",
            "tooDeep|TREE_TOO_DEEP|trigger.children[0].children[0].children[0].children[0]",
            "idleCount|IDLE_CPU_COUNT_OUT_OF_RANGE|trigger.children[1].child.count",
            "unknownAmountKind|UNKNOWN_KIND|action.amount.type", "zeroBatch|AMOUNT_NOT_POSITIVE|action.amount.target",
            "specificWithoutKey|MISSING_FIELD|action.cpu.key",
            "lightMode|CAPABILITY_UNAVAILABLE|action.options.lightMode",
            "tooLarge|TREE_TOO_LARGE|trigger.children[31]" })
    void aDefinitionThatBreaksARuleIsRefusedWithTheReasonAndThePathOfTheMember(String fault, String reason, String path)
        throws Exception {
        JsonObject body = platesBelow(definition -> {
            switch (fault) {
                case "emptyName" -> definition.addProperty("name", "");
                case "paddedName" -> definition.addProperty("name", " padded");
                case "controlName" -> definition.addProperty("name", "tab\there");
                case "longCooldown" -> definition.addProperty("cooldownSeconds", 604801);
                case "zeroAmount" -> child(trigger(definition), 0).addProperty("amount", 0);
                case "unknownKind" -> child(trigger(definition), 0).addProperty("type", "stockSideways");
                case "missingAmount" -> child(trigger(definition), 0).remove("amount");
                case "strayResource" -> child(trigger(definition), 1).getAsJsonObject("child")
                    .addProperty("resource", key(plate));
                case "emptyGroup" -> trigger(definition).add("children", new JsonArray());
                case "tooDeep" -> {
                    JsonObject nest = child(trigger(definition), 0);
                    nest.addProperty("type", "all");
                    nest.remove("resource");
                    nest.remove("amount");
                    nest.add(
                        "children",
                        new JsonParser().parse(
                            "[{\"type\":\"all\",\"children\":[{\"type\":\"all\",\"children\":[{\"type\":\"idleCpusAtLeast\",\"count\":1}]}]}]"));
                }
                case "idleCount" -> child(trigger(definition), 1).getAsJsonObject("child")
                    .addProperty("count", 0);
                case "unknownAmountKind" -> object(definition, "action", "amount").addProperty("type", "fillUp");
                case "zeroBatch" -> object(definition, "action", "amount").addProperty("target", 0);
                case "specificWithoutKey" -> object(definition, "action", "cpu").addProperty("type", "specific");
                case "lightMode" -> object(definition, "action", "options").addProperty("lightMode", true);
                case "tooLarge" -> {
                    JsonArray children = new JsonArray();
                    for (int index = 0; index < 32; index++)
                        children.add(new JsonParser().parse("{\"type\":\"idleCpusAtLeast\",\"count\":1}"));
                    trigger(definition).add("children", children);
                }
                default -> throw new AssertionError(fault);
            }
        });
        assertRejected(post(OWNER, body), HTTP_BAD_REQUEST, "INVALID_STANDING_ORDER", reason, path);
        assertEquals(
            0,
            list(OWNER).getAsJsonArray("orders")
                .size(),
            "A refused definition stores nothing");
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        assertRejected(
            request("PATCH", orders + "/" + seeded.key(), OWNER, body.toString()),
            HTTP_BAD_REQUEST,
            "INVALID_STANDING_ORDER",
            reason,
            path);
        assertEquals(
            "Seeded",
            ((StandingOrderStore.Valid) CoreEngine.getStandingOrderStore()
                .get(gridKey, seeded.key())).definition()
                .name());
    }

    @Test
    void aCraftingOptionIsAcceptedOnceTheServerSupportsIt() throws Exception {
        JsonObject body = platesBelow(
            definition -> object(definition, "action", "options").addProperty("lightMode", true));
        lightModeSupported.set(true);
        JsonObject listing = data(post(OWNER, body));
        assertTrue(
            listing.getAsJsonArray("orders")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("definition")
                .getAsJsonObject("action")
                .getAsJsonObject("options")
                .get("lightMode")
                .getAsBoolean());
    }

    @Test
    void malformedJsonIsABadRequestWithoutAPath() throws Exception {
        for (String body : new String[] { "", "{}", "[]", "null", "{\"name\":\"x\"}",
            platesBelow("A").replace("\"amount\":100", "\"amount\":\"100\""),
            platesBelow("A").replace("\"amount\":100", "\"amount\":1.5"),
            platesBelow("A").replace("\"enabled\":true", "\"enabled\":null"),
            platesBelow("A").replace("\"cooldownSeconds\":30", "\"cooldownSeconds\":30,\"extra\":1"),
            platesBelow("A").replace(key(plate), "not-a-key"),
            platesBelow("A").replace("\"children\":[{", "\"children\":[7,{"),
            platesBelow("A").replace("\"type\":\"any\"", "\"type\":5") }) {
            Reply reply = request("POST", orders, OWNER, body);
            assertRejected(reply, HTTP_BAD_REQUEST, "BAD_PARAM", null, null);
        }
        assertEquals(
            0,
            list(OWNER).getAsJsonArray("orders")
                .size());
    }

    @Test
    void aResourceTheNetworkNeverStoredAndNoTerminalDescribesIsNotFound() throws Exception {
        JsonObject unknown = platesBelow(definition -> object(definition, "action").addProperty("resource", key(dust)));
        assertRejected(post(OWNER, unknown), HTTP_NOT_FOUND, "ITEM_NOT_FOUND", null, null);
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        assertRejected(
            request("PATCH", orders + "/" + seeded.key(), OWNER, unknown.toString()),
            HTTP_NOT_FOUND,
            "ITEM_NOT_FOUND",
            null,
            null);
        assertEquals(
            Collections.singletonList(seeded),
            CoreEngine.getStandingOrderStore()
                .list(gridKey));
        describeResources(plate, ingot, dust);
        assertEquals(
            2,
            data(post(OWNER, unknown)).getAsJsonArray("orders")
                .size());
    }

    @Test
    void aNetworkHoldsAtMostTheConfiguredNumberOfOrders() throws Exception {
        Config.INSTANCE.standingOrders.maxOrdersPerGrid = 1;
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        assertEquals(
            1,
            list(OWNER).get("maxOrders")
                .getAsInt());
        assertRejected(
            post(OWNER, platesBelow(definition -> {})),
            HTTP_CONFLICT,
            "STANDING_ORDER_LIMIT_REACHED",
            null,
            null);
        assertEquals(
            Collections.singletonList(seeded),
            CoreEngine.getStandingOrderStore()
                .list(gridKey));
        assertEquals(
            HTTP_OK,
            request(
                "PATCH",
                orders + "/" + seeded.key(),
                OWNER,
                platesBelow(definition -> definition.addProperty("name", "Edit at capacity")).toString()).status());
    }

    // ----- Authorization -----

    @Test
    void readingNeedsNetworkAccessButNeitherThePlayersSettingNorTheSwitches() throws Exception {
        seed("Seeded", true, plate.web$getKey());
        assertRejected(request("GET", orders, STRANGER, ""), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
        assertRejected(request("GET", orders, "", ""), HTTP_UNAUTHORIZED, "UNAUTHORIZED", null, null);
        Config.INSTANCE.standingOrders.allowPlayers = false;
        JsonObject asOwner = list(OWNER);
        assertFalse(
            asOwner.get("canEdit")
                .getAsBoolean());
        assertTrue(
            asOwner.get("serverEnabled")
                .getAsBoolean());
        assertTrue(
            list(ADMIN).get("canEdit")
                .getAsBoolean());
        Config.INSTANCE.standingOrders.enabled = false;
        for (String headers : new String[] { OWNER, ADMIN }) {
            JsonObject listing = list(headers);
            assertFalse(
                listing.get("serverEnabled")
                    .getAsBoolean());
            assertFalse(
                listing.get("canEdit")
                    .getAsBoolean());
            assertEquals(
                1,
                listing.getAsJsonArray("orders")
                    .size());
        }
        Config.INSTANCE.standingOrders.enabled = true;
        GridSwitch.set(gridKey, false);
        assertFalse(
            list(OWNER).get("gridEnabled")
                .getAsBoolean());
    }

    @Test
    void changingNeedsNetworkAccessAndThePlayersSettingUnlessTheAccountIsAnAdministrator() throws Exception {
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        String order = orders + "/" + seeded.key();
        String valid = platesBelow(definition -> {}).toString();
        for (String headers : new String[] { STRANGER }) {
            assertRejected(request("POST", orders, headers, valid), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
            assertRejected(request("PATCH", order, headers, valid), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
            assertRejected(request("DELETE", order, headers, ""), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
            assertRejected(request("POST", order + "/run", headers, ""), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
        }
        Config.INSTANCE.standingOrders.allowPlayers = false;
        String invalid = platesBelow(definition -> definition.addProperty("name", "")).toString();
        assertRejected(request("POST", orders, OWNER, valid), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
        assertRejected(request("POST", orders, OWNER, invalid), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
        assertRejected(request("PATCH", order, OWNER, valid), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
        assertRejected(request("DELETE", order, OWNER, ""), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
        assertRejected(request("POST", order + "/run", OWNER, ""), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
        assertEquals(
            Collections.singletonList(seeded),
            CoreEngine.getStandingOrderStore()
                .list(gridKey));
        assertEquals(HTTP_OK, request("PATCH", order, ADMIN, valid).status());
        assertEquals(HTTP_ACCEPTED, request("POST", order + "/run", ADMIN, "").status());
        assertEquals(HTTP_OK, request("DELETE", order, ADMIN, "").status());
    }

    @Test
    void anAdministratorMayCreateOrdersWhenPlayersMayNot() throws Exception {
        Config.INSTANCE.standingOrders.allowPlayers = false;
        assertRejected(post(OWNER, platesBelow(definition -> {})), HTTP_FORBIDDEN, "NO_PERMISSIONS", null, null);
        assertEquals(
            1,
            data(post(ADMIN, platesBelow(definition -> {}))).getAsJsonArray("orders")
                .size());
    }

    @Test
    void aDisabledServerFeatureRefusesEveryChangeEvenFromAnAdministrator() throws Exception {
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        String order = orders + "/" + seeded.key();
        String valid = platesBelow(definition -> {}).toString();
        Config.INSTANCE.standingOrders.enabled = false;
        for (String headers : new String[] { OWNER, ADMIN }) {
            assertRejected(
                request("POST", orders, headers, valid),
                HTTP_FORBIDDEN,
                "STANDING_ORDERS_DISABLED",
                null,
                null);
            assertRejected(
                request("PATCH", order, headers, valid),
                HTTP_FORBIDDEN,
                "STANDING_ORDERS_DISABLED",
                null,
                null);
            assertRejected(
                request("DELETE", order, headers, ""),
                HTTP_FORBIDDEN,
                "STANDING_ORDERS_DISABLED",
                null,
                null);
            assertRejected(
                request("POST", order + "/run", headers, ""),
                HTTP_FORBIDDEN,
                "STANDING_ORDERS_DISABLED",
                null,
                null);
        }
        assertEquals(
            Collections.singletonList(seeded),
            CoreEngine.getStandingOrderStore()
                .list(gridKey));
        Config.INSTANCE.standingOrders.enabled = true;
        assertEquals(HTTP_OK, request("PATCH", order, OWNER, valid).status());
    }

    @Test
    void cookieAndLocalMutationsNeedTheRequestMarkerLikeEveryOtherMutation() throws Exception {
        StandingOrderStore.Valid seeded = seed("Seeded", true, plate.web$getKey());
        String order = orders + "/" + seeded.key();
        String valid = platesBelow(definition -> {}).toString();
        String cookie = "Cookie: authenticationToken=owner\r\n" + JSON;
        assertRejected(request("POST", orders, cookie, valid), HTTP_FORBIDDEN, "CSRF_REJECTED", null, null);
        assertRejected(request("PATCH", order, cookie, valid), HTTP_FORBIDDEN, "CSRF_REJECTED", null, null);
        assertRejected(request("DELETE", order, cookie, ""), HTTP_FORBIDDEN, "CSRF_REJECTED", null, null);
        assertRejected(request("POST", order + "/run", cookie, ""), HTTP_FORBIDDEN, "CSRF_REJECTED", null, null);
        assertEquals(
            Collections.singletonList(seeded),
            CoreEngine.getStandingOrderStore()
                .list(gridKey));
        assertEquals(HTTP_OK, request("GET", orders, cookie, "").status());
        assertEquals(HTTP_OK, request("PATCH", order, cookie + "X-AE2-Request: true\r\n", valid).status());
    }

    @Test
    void bodiesMustBeJsonAndSmall() throws Exception {
        String valid = platesBelow(definition -> {}).toString();
        assertRejected(
            request("POST", orders, "Authorization: Bearer owner\r\nContent-Type: text/plain\r\n", valid),
            HTTP_UNSUPPORTED_TYPE,
            "UNSUPPORTED_MEDIA_TYPE",
            null,
            null);
        assertRejected(
            request("POST", orders, OWNER, String.join("", Collections.nCopies(8193, " "))),
            HTTP_ENTITY_TOO_LARGE,
            "REQUEST_TOO_LARGE",
            null,
            null);
        assertEquals(
            0,
            list(OWNER).getAsJsonArray("orders")
                .size());
    }

    @Test
    void aLargestDefinitionFitsTheBodyLimit() throws Exception {
        StringBuilder children = new StringBuilder();
        for (int index = 0; index < 31; index++) {
            if (index > 0) children.append(',');
            children.append("{\"type\":\"stockAtLeast\",\"resource\":\"")
                .append(key(plate))
                .append("\",\"amount\":9223372036854775807}");
        }
        JsonObject largest = platesBelow(definition -> {
            definition.add("trigger", new JsonParser().parse("{\"type\":\"any\",\"children\":[" + children + "]}"));
            definition.addProperty("name", String.join("", Collections.nCopies(128, "é")));
        });
        assertTrue(
            largest.toString()
                .getBytes(UTF_8).length < 8192);
        assertEquals(
            1,
            data(post(OWNER, largest)).getAsJsonArray("orders")
                .size());
    }

    // ----- Rate limit -----

    @Test
    void changesThatCostAReadAreLimitedPerNetworkWhileOtherEditsAreNot() throws Exception {
        JsonObject listing = data(post(OWNER, platesBelow(definition -> {})));
        String order = orders + "/" + orderKey(listing);
        assertRejected(
            post(OWNER, platesBelow(definition -> definition.addProperty("name", "Second"))),
            429,
            "TOO_MANY_REQUESTS",
            null,
            null);
        assertRejected(request("POST", order + "/run", OWNER, ""), 429, "TOO_MANY_REQUESTS", null, null);
        assertRejected(
            request(
                "PATCH",
                order,
                OWNER,
                platesBelow(definition -> object(definition, "action").addProperty("resource", key(ingot))).toString()),
            429,
            "TOO_MANY_REQUESTS",
            null,
            null);
        assertEquals(
            1,
            list(OWNER).getAsJsonArray("orders")
                .size(),
            "Rejected changes store nothing");
        for (String name : new String[] { "Renamed", "Renamed again" }) {
            assertEquals(
                HTTP_OK,
                request(
                    "PATCH",
                    order,
                    OWNER,
                    platesBelow(definition -> definition.addProperty("name", name)).toString()).status());
        }
        assertEquals(
            HTTP_OK,
            request(
                "PATCH",
                order,
                OWNER,
                platesBelow(definition -> definition.addProperty("enabled", false)).toString()).status());
        assertEquals(HTTP_OK, request("DELETE", order, OWNER, "").status());
    }

    // ----- Plumbing -----

    private Reply request(String method, String path, String headers, String body) throws Exception {
        try (Socket socket = new Socket(
            InetAddress.getLoopbackAddress(),
            server.getAddress()
                .getPort())) {
            socket.setSoTimeout(5000);
            byte[] payload = body.getBytes(UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(
                (method + " "
                    + path
                    + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + headers
                    + "Content-Length: "
                    + payload.length
                    + "\r\n\r\n").getBytes(UTF_8));
            output.write(payload);
            output.flush();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            String response = new String(bytes.toByteArray(), UTF_8);
            return new Reply(
                Integer.parseInt(response.split(" ", 3)[1]),
                response.substring(response.indexOf("\r\n\r\n") + 4));
        }
    }

    @Desugar
    private record Reply(int status, String body) {

        JsonObject json() {
            return new JsonParser().parse(body)
                .getAsJsonObject();
        }
    }

    /** Switches standing orders on or off for one network, the way its settings endpoint does. */
    private static final class GridSwitch {

        static void set(@NotNull StableKey gridKey, boolean enabled) throws IOException {
            synchronized (CoreEngine.GRID_IDENTITIES) {
                CoreEngine.GRID_IDENTITIES.getPersistentData(gridKey)
                    .getSettings()
                    .setStandingOrdersEnabled(enabled);
                CoreEngine.GRID_IDENTITIES.saveIfDirty();
            }
        }
    }

    @Desugar
    private record Platform(File configDirectory) implements IServerPlatform {

        @Override
        public Path getModFile() {
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
