package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

/**
 * Reads of the dependency tree through the HTTP router: which requests need the server thread and how
 * batched expansion is bounded.
 */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingPlanTreeBatchHttpTest extends GridTestScope {

    /** Breadth-first order of every node reachable from the target; shared nodes and the cycle appear once. */
    private static final List<String> FULL_WALK = Arrays.asList(
        "RESOURCE:0",
        "RECIPE:0",
        "INGREDIENT:0",
        "INGREDIENT:1",
        "RESOURCE:1",
        "RESOURCE:2",
        "RESOURCE:3",
        "RECIPE:1",
        "RECIPE:2",
        "INGREDIENT:2",
        "INGREDIENT:3",
        "RESOURCE:4",
        "RECIPE:3",
        "INGREDIENT:4");

    private HttpServer server;
    private IAE previousAe;
    private TestGridFixtures.TestGrid grid;
    private final AtomicInteger serverStages = new AtomicInteger();
    private final AtomicBoolean serverThreadStalled = new AtomicBoolean();
    private final AtomicInteger captures = new AtomicInteger();
    private int plan;

    @BeforeEach
    void start() throws Exception {
        grid = TestGridFixtures.grid(310);
        previousAe = AE2Controller.AE2Interface;
        AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
        plan = addPlan(CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(() -> {
            captures.incrementAndGet();
            return graph();
        })));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ApiRouter router = new ApiRouter(exchange -> {
            if ("Bearer owner".equals(
                exchange.getRequestHeaders()
                    .getFirst("Authorization"))) {
                return new AE2Controller.RequestContext(
                    exchange,
                    TestGridFixtures.principal(TestGridFixtures.OWNER_ID));
            }
            return null;
        }, exchange -> false, request -> {
            serverStages.incrementAndGet();
            if (!serverThreadStalled.get()) request.runOnServerThread(AE2Controller.AE2Interface);
        });
        router.register(GetCraftingPlanTree.class);
        server.createContext("/api", router);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        AE2Controller.AE2Interface = previousAe;
        GridData.clearRuntimeState();
    }

    @Test
    void capturedGraphIsServedWhileTheServerThreadIsUnavailable() throws Exception {
        JsonObject first = get("kind=ROOT&limit=1", 200, "OK");
        String captureId = data(first).get("captureId")
            .getAsString();
        assertEquals(1, serverStages.get());
        serverThreadStalled.set(true);
        JsonObject next = get("kind=RESOURCE&id=0&offset=0&limit=1&captureId=" + captureId, 200, "OK");
        assertEquals(
            "READY",
            data(next).get("state")
                .getAsString());
        assertEquals(
            captureId,
            data(next).get("captureId")
                .getAsString());
        JsonObject batch = get("nodes=RESOURCE:0&captureId=" + captureId, 200, "OK");
        assertEquals(
            "READY",
            data(batch).get("state")
                .getAsString());
        assertEquals(1, captures.get());
        assertEquals(1, serverStages.get(), "A captured graph must not wait for the server thread");
    }

    @Test
    void firstOpenOfAPlanAndPendingCalculationsStillUseTheServerStage() throws Exception {
        CompletableFuture<IAECraftingJob> pending = new CompletableFuture<>();
        int calculating = addPlan(pending);
        assertEquals(
            "CALCULATING",
            data(get(calculating, "", 200, "OK")).get("state")
                .getAsString());
        assertEquals(
            "CALCULATING",
            data(get(calculating, "", 200, "OK")).get("state")
                .getAsString());
        assertEquals(2, serverStages.get());
        pending.complete(CraftingPlanTreePreparationTest.job(CraftingPlanTreeBatchHttpTest::graph));
        assertEquals(
            "READY",
            data(get(calculating, "", 200, "OK")).get("state")
                .getAsString());
        assertEquals(3, serverStages.get());
        get(calculating, "limit=1", 200, "OK");
        assertEquals(3, serverStages.get());
    }

    @Test
    void accessRevokedBetweenRequestsIsRejectedWithoutReleasingCapturedData() throws Exception {
        get("", 200, "OK");
        grid.withoutSources();
        for (String query : new String[] { "", "nodes=RESOURCE:0", "kind=RECIPE&id=0" }) {
            JsonObject denied = get(query, 403, "NO_PERMISSIONS");
            assertFalse(
                denied.toString()
                    .contains("captureId"),
                denied.toString());
        }
    }

    @Test
    void removedPlansAndReplacedCapturesAreRejectedOnTheWorkerPath() throws Exception {
        String captureId = data(get("", 200, "OK")).get("captureId")
            .getAsString();
        get("nodes=RESOURCE:0&captureId=other", 409, "UPDATE_CHANGED");
        GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .removeJob(plan);
        get("captureId=" + captureId, 404, "INVALID_ID");
        get(plan + 1000, "", 404, "INVALID_ID");
    }

    @Test
    void batchExpandsBreadthFirstAndNeverRepeatsSharedOrCyclicNodes() throws Exception {
        JsonObject batch = data(get("nodes=RESOURCE:0", 200, "OK"));
        List<String> keys = new ArrayList<>();
        for (JsonElement page : batch.getAsJsonArray("pages")) {
            JsonObject object = page.getAsJsonObject();
            keys.add(key(object.getAsJsonObject("node")));
            assertEquals(
                0,
                object.get("offset")
                    .getAsInt());
            assertTrue(
                object.get("nextOffset")
                    .isJsonNull());
        }
        assertEquals(FULL_WALK, keys);
        assertEquals(
            9007199254740993L,
            batch.getAsJsonObject("root")
                .get("quantity")
                .getAsLong());
        assertEquals(
            0,
            batch.getAsJsonArray("children")
                .size());
        Set<Integer> described = new HashSet<>();
        for (JsonElement resource : batch.getAsJsonArray("resources")) assertTrue(
            described.add(
                resource.getAsJsonObject()
                    .get("id")
                    .getAsInt()),
            "Descriptors are deduplicated per response");
        assertEquals(5, described.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void everyBatchPageEqualsTheSingleNodePageForTheSameNode(boolean missingOnly) throws Exception {
        String suffix = missingOnly ? "&missingOnly=true" : "";
        JsonObject batch = data(get("nodes=RESOURCE:0,RECIPE:0" + suffix, 200, "OK"));
        assertEquals(
            missingOnly,
            batch.get("missingOnly")
                .getAsBoolean());
        assertTrue(
            batch.getAsJsonArray("pages")
                .size() > 0);
        for (JsonElement element : batch.getAsJsonArray("pages")) {
            JsonObject page = element.getAsJsonObject();
            JsonObject node = page.getAsJsonObject("node");
            JsonObject single = data(
                get(
                    "kind=" + node.get("kind")
                        .getAsString()
                        + "&id="
                        + node.get("id")
                            .getAsInt()
                        + "&limit=100"
                        + suffix,
                    200,
                    "OK"));
            assertEquals(single.get("node"), page.get("node"));
            assertEquals(single.get("children"), page.get("children"));
            assertEquals(single.get("nextOffset"), page.get("nextOffset"));
        }
    }

    @Test
    void rowBudgetStopsTheBatchAndPerNodeContinuationsResumeExactly() throws Exception {
        JsonObject cut = data(get("nodes=RESOURCE:0&rows=2", 200, "OK"));
        JsonArray pages = cut.getAsJsonArray("pages");
        assertEquals(2, pages.size());
        assertTrue(
            pages.get(0)
                .getAsJsonObject()
                .get("nextOffset")
                .isJsonNull());
        JsonObject truncated = pages.get(1)
            .getAsJsonObject();
        assertEquals("RECIPE:0", key(truncated.getAsJsonObject("node")));
        assertEquals(
            1,
            truncated.getAsJsonArray("children")
                .size());
        assertEquals(
            1,
            truncated.get("nextOffset")
                .getAsInt());
        JsonObject resumed = data(get("nodes=RECIPE:0:1&rows=1", 200, "OK"));
        JsonObject tail = resumed.getAsJsonArray("pages")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            1,
            tail.get("offset")
                .getAsInt());
        assertEquals(
            1,
            tail.getAsJsonArray("children")
                .size());
        assertTrue(
            tail.get("nextOffset")
                .isJsonNull());
        assertEquals(
            1,
            resumed.getAsJsonArray("pages")
                .size(),
            "The budget was used up by the continuation");
    }

    @Test
    void requestedNodesBeyondTheBudgetAreLeftForTheNextRequest() throws Exception {
        JsonObject batch = data(get("nodes=RECIPE:0,RECIPE:1,RECIPE:2&rows=2", 200, "OK"));
        JsonArray pages = batch.getAsJsonArray("pages");
        assertEquals(1, pages.size());
        assertEquals(
            "RECIPE:0",
            key(
                pages.get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("node")));
    }

    @Test
    void alreadyLoadedNodesAreNotExpandedAgain() throws Exception {
        JsonObject batch = data(get("nodes=RESOURCE:0&loaded=RECIPE:0,1", 200, "OK"));
        JsonArray pages = batch.getAsJsonArray("pages");
        assertEquals(1, pages.size(), "The only child of the target was reported as loaded");
        JsonObject skipped = data(get("nodes=RECIPE:0&loaded=RECIPE:0|RESOURCE:0|INGREDIENT:0-1", 200, "OK"));
        assertEquals(
            1,
            skipped.getAsJsonArray("pages")
                .size(),
            "Requested nodes are always read; their loaded children are not");
    }

    @Test
    void repeatedBatchesReachEveryNodeOnceInFarFewerRequestsThanOnePerNode() throws Exception {
        Set<String> complete = new LinkedHashSet<>();
        Map<String, Integer> delivered = new HashMap<>();
        Map<String, Integer> frontier = new LinkedHashMap<>();
        frontier.put("RESOURCE:0", 0);
        int requests = 0;
        while (!frontier.isEmpty()) {
            assertTrue(++requests < 20, "No progress");
            List<String> tokens = new ArrayList<>();
            for (Map.Entry<String, Integer> next : frontier.entrySet()) {
                if (tokens.size() == 3) break;
                tokens.add(next.getKey() + ":" + next.getValue());
            }
            StringBuilder loaded = new StringBuilder();
            for (String key : complete) {
                if (loaded.length() > 0) loaded.append('|');
                loaded.append(key);
            }
            JsonObject batch = data(
                get(
                    "nodes=" + String.join(",", tokens) + "&rows=4" + (loaded.length() == 0 ? "" : "&loaded=" + loaded),
                    200,
                    "OK"));
            assertTrue(
                batch.getAsJsonArray("pages")
                    .size() > 0);
            for (JsonElement element : batch.getAsJsonArray("pages")) {
                JsonObject page = element.getAsJsonObject();
                String key = key(page.getAsJsonObject("node"));
                frontier.remove(key);
                if (page.get("offset")
                    .getAsInt() == 0) delivered.merge(key, 1, Integer::sum);
                if (page.get("nextOffset")
                    .isJsonNull()) complete.add(key);
                else frontier.put(
                    key,
                    page.get("nextOffset")
                        .getAsInt());
                for (JsonElement child : page.getAsJsonArray("children")) {
                    String childKey = key(
                        child.getAsJsonObject()
                            .getAsJsonObject("node"));
                    if (!complete.contains(childKey) && !delivered.containsKey(childKey))
                        frontier.putIfAbsent(childKey, 0);
                }
            }
        }
        assertEquals(new HashSet<>(FULL_WALK), complete);
        for (Map.Entry<String, Integer> count : delivered.entrySet()) assertEquals(1, count.getValue(), count.getKey());
        assertTrue(requests < FULL_WALK.size(), "Batches should need fewer requests than nodes: " + requests);
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "nodes=RESOURCE:0&rows=0", "nodes=RESOURCE:0&rows=1001", "nodes=ROOT", "nodes=ROOT:0",
            "nodes=RESOURCE", "nodes=RESOURCE:x", "nodes=RESOURCE:-1", "nodes=RESOURCE:0:-1", "nodes=BOGUS:0",
            "nodes=RESOURCE:0:0:0", "nodes=UNPLACED:3", "nodes=RESOURCE:99", "nodes=RECIPE:99", "nodes=INGREDIENT:99",
            "nodes=RECIPE:0:99", "nodes=RESOURCE:0,RECIPE:99", "nodes=", "nodes=,", "nodes=RESOURCE:0&loaded=RESOURCE",
            "nodes=RESOURCE:0&loaded=RESOURCE:a-b", "nodes=RESOURCE:0&loaded=RESOURCE:5-2",
            "nodes=RESOURCE:0&loaded=ROOT:0", "loaded=RECIPE:0", "nodes=RESOURCE:0&rows=x" })
    void invalidBatchParametersAreRejectedBeforeAnyPageIsRead(String query) throws Exception {
        get(query, 400, "BAD_PARAM");
    }

    @Test
    void tooManyStartNodesAreRejected() throws Exception {
        StringBuilder nodes = new StringBuilder("nodes=RESOURCE:0");
        for (int i = 1; i <= 64; i++) nodes.append(",RESOURCE:")
            .append(i % 5);
        get(nodes.toString(), 400, "BAD_PARAM");
    }

    @Test
    void duplicateStartNodesCollapseIntoOnePage() throws Exception {
        JsonObject batch = data(get("nodes=RECIPE:0,RECIPE:0&rows=2", 200, "OK"));
        assertEquals(
            1,
            batch.getAsJsonArray("pages")
                .size());
    }

    @Test
    void unplacedAndMissingGroupsAreValidBatchStartsAndPreparationStatesCarryNoPages() throws Exception {
        JsonObject groups = data(get("nodes=UNPLACED:0,UNLINKED_MISSING:0", 200, "OK"));
        assertEquals(
            2,
            groups.getAsJsonArray("pages")
                .size());
        CompletableFuture<IAECraftingJob> pending = new CompletableFuture<>();
        int calculating = addPlan(pending);
        JsonObject waiting = data(get(calculating, "nodes=RESOURCE:0", 200, "OK"));
        assertEquals(
            "CALCULATING",
            waiting.get("state")
                .getAsString());
        assertTrue(
            waiting.get("pages")
                .isJsonNull()
                || waiting.getAsJsonArray("pages")
                    .size() == 0);
    }

    private int addPlan(CompletableFuture<IAECraftingJob> job) {
        return GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(job, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
    }

    private static CraftingRecipeCapture graph() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(100, 10_000);
        for (int i = 0; i < 5; i++) capture.addResource(
            () -> TestGridFixtures.planOutput()
                .description());
        capture.result(0, 9007199254740993L, false);
        int target = capture.addRecipe(1);
        int first = capture.addIngredient(target, 1);
        capture.addCandidate(first, 1, 1);
        capture.addCandidate(first, 2, 1);
        int second = capture.addIngredient(target, 1);
        capture.addCandidate(second, 3, 1);
        capture.addOutput(target, 0, 1, true);
        capture.finishRecipe(target);
        int producerA = capture.addRecipe(1);
        int shared = capture.addIngredient(producerA, 1);
        capture.addCandidate(shared, 4, 1);
        capture.addOutput(producerA, 1, 1, true);
        capture.finishRecipe(producerA);
        int producerB = capture.addRecipe(1);
        int sharedAgain = capture.addIngredient(producerB, 1);
        capture.addCandidate(sharedAgain, 4, 1);
        capture.addOutput(producerB, 2, 1, true);
        capture.finishRecipe(producerB);
        int loop = capture.addRecipe(1);
        int back = capture.addIngredient(loop, 1);
        capture.addCandidate(back, 0, 1);
        capture.addOutput(loop, 4, 1, true);
        capture.finishRecipe(loop);
        return capture.build();
    }

    private static String key(JsonObject node) {
        return node.get("kind")
            .getAsString() + ":"
            + node.get("id")
                .getAsInt();
    }

    private static JsonObject data(JsonObject response) {
        return response.getAsJsonObject("data");
    }

    private JsonObject get(String query, int expectedCode, String expectedStatus) throws Exception {
        return get(plan, query, expectedCode, expectedStatus);
    }

    private JsonObject get(int planId, String query, int expectedCode, String expectedStatus) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://localhost:" + server.getAddress()
                .getPort()
                + "/api/grids/"
                + TestGridFixtures.resolvedKey(grid)
                + "/crafting-plans/"
                + planId
                + "/tree"
                + (query.isEmpty() ? "" : "?" + query.replace("|", "%7C")))
            .openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Authorization", "Bearer owner");
        try {
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                JsonObject response = new JsonParser().parse(IOUtils.toString(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                assertEquals(expectedCode, status, response.toString());
                assertEquals(
                    expectedStatus,
                    response.get("status")
                        .getAsString());
                return response;
            }
        } finally {
            connection.disconnect();
        }
    }
}
