package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture;
import pl.kuba6000.ae2webintegration.core.crafting.TraceFixtures;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

/** The tree endpoint over per-occurrence traces: node shapes, shared tasks, missing paths and batched reads. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingPlanTraceRequestTest extends GridTestScope {

    private static final int OWNER = 7;

    @AfterEach
    void clearPlans() {
        GridData.clearRuntimeState();
    }

    @Test
    void theRootRequestListsItsContributionsInNativeOrderAndAnnouncesTraceMode() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(400);
        AtomicInteger captures = new AtomicInteger();
        int plan = addTrace(grid, TraceFixtures::gears, captures);
        JsonObject data = read(grid, plan, "");
        assertEquals(
            "TRACE",
            data.get("mode")
                .getAsString());
        assertEquals(
            "READY",
            data.get("state")
                .getAsString());
        JsonObject root = data.getAsJsonObject("root");
        assertEquals(
            "4",
            root.get("quantity")
                .getAsString());
        assertFalse(
            root.get("simulated")
                .getAsBoolean());
        assertEquals(
            "Machine",
            resource(
                data,
                root.get("resourceId")
                    .getAsInt())
                .get("displayName")
                .getAsString());
        JsonObject node = data.getAsJsonObject("node");
        assertEquals(
            "REQUEST",
            node.get("kind")
                .getAsString());
        assertEquals(
            0,
            node.get("id")
                .getAsInt());
        JsonArray children = data.getAsJsonArray("children");
        assertEquals(2, children.size());
        JsonObject storage = children.get(0)
            .getAsJsonObject();
        assertEquals(
            "CONTRIBUTION",
            storage.get("relation")
                .getAsString());
        assertEquals(
            "1",
            storage.get("quantity")
                .getAsString());
        assertEquals(
            "STORAGE",
            storage.getAsJsonObject("node")
                .get("contribution")
                .getAsString());
        JsonObject craft = children.get(1)
            .getAsJsonObject();
        assertEquals(
            "CRAFT",
            craft.get("relation")
                .getAsString());
        assertEquals(
            "3",
            craft.get("quantity")
                .getAsString());
        JsonObject task = craft.getAsJsonObject("node");
        assertEquals(
            "TASK",
            task.get("kind")
                .getAsString());
        assertEquals(
            "3",
            task.get("crafts")
                .getAsString());
        assertTrue(
            task.get("complete")
                .getAsBoolean());
        assertEquals(
            1,
            task.getAsJsonArray("outputs")
                .size());
        JsonArray inputs = task.getAsJsonArray("inputs");
        assertEquals(3, inputs.size());
        assertEquals(
            "2",
            inputs.get(0)
                .getAsJsonObject()
                .get("quantity")
                .getAsString());
        for (JsonElement input : inputs) resource(
            data,
            input.getAsJsonObject()
                .get("resourceId")
                .getAsInt());
        assertEquals(
            0,
            data.getAsJsonArray("limits")
                .size());
        assertEquals(1, captures.get());
    }

    @Test
    void aTaskListsTheRequestsItIssuedAndASharedTaskIsOneNodeKeepingEachRequestsAmount() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(401);
        int plan = addTrace(grid, TraceFixtures::gears, new AtomicInteger());
        JsonObject shared = read(grid, plan, "kind=TASK&id=1");
        JsonObject cut = shared.getAsJsonObject("node");
        assertEquals(
            "3",
            cut.get("crafts")
                .getAsString());
        JsonArray outputs = cut.getAsJsonArray("outputs");
        assertEquals(2, outputs.size());
        assertFalse(
            outputs.get(1)
                .getAsJsonObject()
                .get("primary")
                .getAsBoolean());
        JsonObject ingots = shared.getAsJsonArray("children")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "CHILD_REQUEST",
            ingots.get("relation")
                .getAsString());
        assertEquals(
            "3",
            ingots.get("quantity")
                .getAsString());
        assertEquals(
            4,
            ingots.getAsJsonObject("node")
                .get("id")
                .getAsInt());
        // R1 (four gears) and R5 (two gears) reach the same task node with their own crafted amounts.
        assertEquals(
            "4",
            craftOf(read(grid, plan, "kind=REQUEST&id=1")).get("quantity")
                .getAsString());
        JsonObject second = craftOf(read(grid, plan, "kind=REQUEST&id=5"));
        assertEquals(
            "2",
            second.get("quantity")
                .getAsString());
        assertEquals(
            1,
            second.getAsJsonObject("node")
                .get("id")
                .getAsInt());
        assertEquals(
            "REUSED",
            read(grid, plan, "kind=REQUEST&id=5").getAsJsonArray("children")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("node")
                .get("contribution")
                .getAsString());
    }

    @Test
    void everyLeafKindIsCarriedWithItsOwnAmountAndTheRequestedResource() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(402);
        int plan = addTrace(grid, TraceFixtures::gears, new AtomicInteger());
        JsonArray plates = read(grid, plan, "kind=REQUEST&id=2").getAsJsonArray("children");
        assertEquals("STORAGE:1,EMITTED:2,CRAFT:3", describe(plates));
        JsonArray ingots = read(grid, plan, "kind=REQUEST&id=4").getAsJsonArray("children");
        assertEquals("MISSING:2,UNKNOWN:1", describe(ingots));
        JsonObject leaf = ingots.get(0)
            .getAsJsonObject()
            .getAsJsonObject("node");
        assertEquals(
            TraceFixtures.INGOT,
            leaf.get("resourceId")
                .getAsInt());
    }

    @Test
    void missingOnlyKeepsPathsToMissingContributionsAndReportsPlanTotals() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(403);
        int plan = addTrace(grid, TraceFixtures::gears, new AtomicInteger());
        JsonObject root = read(grid, plan, "missingOnly=true");
        assertTrue(
            root.get("missingOnly")
                .getAsBoolean());
        assertTrue(
            root.get("missingComplete")
                .getAsBoolean());
        assertEquals(
            1,
            root.get("missingResourceCount")
                .getAsInt());
        assertEquals(
            0,
            root.get("unlinkedMissingCount")
                .getAsInt());
        assertEquals("CRAFT:3", describe(root.getAsJsonArray("children")));
        assertEquals(
            "CHILD_REQUEST:6,CHILD_REQUEST:6",
            describeLinks(read(grid, plan, "kind=TASK&id=0&missingOnly=true").getAsJsonArray("children")));
        assertEquals(
            0,
            read(grid, plan, "kind=REQUEST&id=3&missingOnly=true").getAsJsonArray("children")
                .size());
        JsonObject ingots = read(grid, plan, "kind=REQUEST&id=4&missingOnly=true");
        assertEquals("MISSING:2", describe(ingots.getAsJsonArray("children")));
        assertEquals(
            "2",
            resource(ingots, TraceFixtures.INGOT).get("missingAmount")
                .getAsString());
        JsonObject unfiltered = read(grid, plan, "kind=REQUEST&id=0");
        assertEquals(
            2,
            unfiltered.getAsJsonArray("children")
                .size());
    }

    @Test
    void aBatchWalksTheWholeTraceOnceInBreadthFirstOrderWithoutPagesForLeaves() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(404);
        int plan = addTrace(grid, TraceFixtures::gears, new AtomicInteger());
        JsonObject batch = read(grid, plan, "nodes=REQUEST:0&rows=1000");
        assertEquals(
            "TRACE",
            batch.get("mode")
                .getAsString());
        assertTrue(
            batch.get("node")
                .isJsonNull());
        assertEquals(
            Arrays.asList(
                "REQUEST:0",
                "TASK:0",
                "REQUEST:1",
                "REQUEST:2",
                "REQUEST:3",
                "TASK:1",
                "TASK:2",
                "REQUEST:4",
                "REQUEST:5"),
            pageKeys(batch));
        JsonObject missing = read(grid, plan, "nodes=REQUEST:0&rows=1000&missingOnly=true");
        assertEquals(
            Arrays
                .asList("REQUEST:0", "TASK:0", "REQUEST:1", "REQUEST:2", "TASK:1", "TASK:2", "REQUEST:4", "REQUEST:5"),
            pageKeys(missing));
    }

    @Test
    void aBatchIsBoundedByRowsContinuesAPageAndSkipsNodesTheCallerAlreadyHolds() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(405);
        int plan = addTrace(grid, TraceFixtures::gears, new AtomicInteger());
        // R0 costs 2, T0 costs 3, R1 costs 2; R2 gets the one row left and is cut after its first child.
        JsonObject bounded = read(grid, plan, "nodes=REQUEST:0&rows=8");
        JsonArray pages = bounded.getAsJsonArray("pages");
        assertEquals(Arrays.asList("REQUEST:0", "TASK:0", "REQUEST:1", "REQUEST:2"), pageKeys(bounded));
        JsonObject cut = pages.get(3)
            .getAsJsonObject();
        assertEquals(
            1,
            cut.getAsJsonArray("children")
                .size());
        assertEquals(
            1,
            cut.get("nextOffset")
                .getAsInt());
        JsonObject rest = read(grid, plan, "nodes=REQUEST:2:1&rows=1000&loaded=TASK:1");
        JsonObject next = rest.getAsJsonArray("pages")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            1,
            next.get("offset")
                .getAsInt());
        assertEquals("EMITTED:2,CRAFT:3", describe(next.getAsJsonArray("children")));
        // TASK:1 is held, so the continuation reaches TASK:2 and below but not the held branch.
        assertEquals(Arrays.asList("REQUEST:2", "TASK:2", "REQUEST:5"), pageKeys(rest));
        assertEquals(
            Collections.singletonList("REQUEST:0"),
            pageKeys(read(grid, plan, "nodes=REQUEST:0&rows=1000&loaded=TASK:0")));
    }

    @Test
    void recursionThroughASharedTaskTerminatesAndReferencesTheTaskAgain() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(406);
        int plan = addTrace(grid, TraceFixtures::recursion, new AtomicInteger());
        JsonObject batch = read(grid, plan, "nodes=REQUEST:0&rows=1000");
        assertEquals(Arrays.asList("REQUEST:0", "TASK:0", "REQUEST:1", "TASK:1", "REQUEST:2"), pageKeys(batch));
        assertTrue(
            batch.getAsJsonObject("root")
                .get("simulated")
                .getAsBoolean());
        JsonObject closing = batch.getAsJsonArray("pages")
            .get(4)
            .getAsJsonObject()
            .getAsJsonArray("children")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "CRAFT",
            closing.get("relation")
                .getAsString());
        assertEquals(
            0,
            closing.getAsJsonObject("node")
                .get("id")
                .getAsInt());
    }

    @Test
    void aTruncatedTraceIsPartialAndCannotClaimThatNothingIsMissing() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(407);
        int plan = addTrace(grid, () -> {
            CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(4, 1000);
            int resource = trace.addResource(() -> TraceFixtures.descriptor("item"));
            int request = trace.addRequest(resource, 5);
            trace.root(request, false);
            trace.addContribution(request, CraftingTraceCapture.ContributionKind.STORAGE, 1);
            trace.addTask(1);
            trace.addContribution(request, CraftingTraceCapture.ContributionKind.STORAGE, 1);
            return trace.build();
        }, new AtomicInteger());
        JsonObject data = read(grid, plan, "missingOnly=true");
        assertEquals(
            "TRACE",
            data.get("mode")
                .getAsString());
        assertTrue(
            data.get("partial")
                .getAsBoolean());
        assertEquals(
            "ENTRIES",
            data.getAsJsonArray("limits")
                .get(0)
                .getAsString());
        assertFalse(
            data.get("missingComplete")
                .getAsBoolean());
    }

    @Test
    void aggregateCapturesStayAggregateAndKindsOfTheOtherModeAreRejected() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(408);
        int aggregate = addPlan(grid, CraftingPlanTreePreparationTest.job(CraftingPlanTreePreparationTest::example));
        assertEquals(
            "AGGREGATE",
            read(grid, aggregate, "").get("mode")
                .getAsString());
        assertEquals(400, status(grid, aggregate, "kind=REQUEST&id=0"));
        assertEquals(400, status(grid, aggregate, "nodes=TASK:0"));
        int trace = addTrace(grid, TraceFixtures::gears, new AtomicInteger());
        assertEquals(400, status(grid, trace, "kind=RESOURCE&id=0"));
        assertEquals(400, status(grid, trace, "kind=RECIPE&id=0"));
        assertEquals(400, status(grid, trace, "kind=UNPLACED"));
        assertEquals(400, status(grid, trace, "kind=UNLINKED_MISSING&missingOnly=true"));
        assertEquals(400, status(grid, trace, "nodes=RESOURCE:0"));
        // A contribution is a leaf and has no page of its own.
        assertEquals(400, status(grid, trace, "kind=CONTRIBUTION&id=0"));
        assertEquals(400, status(grid, trace, "nodes=CONTRIBUTION:0"));
        assertEquals(400, status(grid, trace, "kind=REQUEST&id=99"));
        assertEquals(400, status(grid, trace, "kind=TASK&id=99"));
        assertEquals(400, status(grid, trace, "kind=REQUEST&id=0&offset=3"));
        assertEquals(400, status(grid, trace, "nodes=TASK:99"));
    }

    @Test
    void aCapturedTraceIsServedWithoutTheServerStageAndKeepsItsCaptureIdentity() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(409);
        AtomicInteger captures = new AtomicInteger();
        int plan = addTrace(grid, TraceFixtures::gears, captures);
        GetCraftingPlanTree uncaptured = request(grid, plan, "");
        assertFalse(uncaptured.serveWithoutServerStage());
        uncaptured.runOnServerThread(TestGridFixtures.ae(grid));
        String captureId = json(
            uncaptured.finishResponse()
                .json())
            .getAsJsonObject("data")
            .get("captureId")
            .getAsString();
        GetCraftingPlanTree later = request(grid, plan, "nodes=REQUEST:0&captureId=" + captureId);
        assertTrue(later.serveWithoutServerStage());
        JsonObject data = json(
            later.finishResponse()
                .json())
            .getAsJsonObject("data");
        assertEquals(
            "TRACE",
            data.get("mode")
                .getAsString());
        assertEquals(
            captureId,
            data.get("captureId")
                .getAsString());
        assertEquals(1, captures.get());
    }

    private static int addTrace(TestGridFixtures.TestGrid grid, Supplier<CraftingTraceCapture> trace,
        AtomicInteger captures) {
        return addPlan(grid, CraftingPlanTreePreparationTest.traceJob(() -> {
            captures.incrementAndGet();
            return trace.get();
        }, () -> { throw new AssertionError("The aggregate is only the fallback"); }));
    }

    private static int addPlan(TestGridFixtures.TestGrid grid, IAECraftingJob job) {
        return GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(job),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
    }

    private static JsonObject craftOf(JsonObject page) {
        for (JsonElement child : page.getAsJsonArray("children")) {
            if ("CRAFT".equals(
                child.getAsJsonObject()
                    .get("relation")
                    .getAsString()))
                return child.getAsJsonObject();
        }
        throw new AssertionError("No craft contribution");
    }

    /** KIND:amount of each child's contribution leaf or CRAFT, comma separated. */
    private static String describe(JsonArray children) {
        List<String> parts = new ArrayList<>();
        for (JsonElement child : children) {
            JsonObject link = child.getAsJsonObject();
            JsonObject node = link.getAsJsonObject("node");
            String kind = "CRAFT".equals(
                link.get("relation")
                    .getAsString()) ? "CRAFT"
                        : node.get("contribution")
                            .getAsString();
            parts.add(
                kind + ":"
                    + link.get("quantity")
                        .getAsString());
        }
        return String.join(",", parts);
    }

    /** RELATION:id of each child link. */
    private static String describeLinks(JsonArray children) {
        List<String> parts = new ArrayList<>();
        for (JsonElement child : children) {
            JsonObject link = child.getAsJsonObject();
            parts.add(
                link.get("relation")
                    .getAsString() + ":"
                    + link.get("quantity")
                        .getAsString());
        }
        return String.join(",", parts);
    }

    private static List<String> pageKeys(JsonObject batch) {
        List<String> keys = new ArrayList<>();
        for (JsonElement page : batch.getAsJsonArray("pages")) {
            JsonObject node = page.getAsJsonObject()
                .getAsJsonObject("node");
            keys.add(
                node.get("kind")
                    .getAsString() + ":"
                    + node.get("id")
                        .getAsInt());
        }
        return keys;
    }

    private static JsonObject resource(JsonObject page, int id) {
        for (JsonElement value : page.getAsJsonArray("resources")) {
            JsonObject resource = value.getAsJsonObject();
            if (resource.get("id")
                .getAsInt() == id) return resource;
        }
        throw new AssertionError("Resource missing from response: " + id);
    }

    private static JsonObject read(TestGridFixtures.TestGrid grid, int plan, String query) {
        GetCraftingPlanTree request = request(grid, plan, query);
        request.runOnServerThread(TestGridFixtures.ae(grid));
        return json(
            request.finishResponse()
                .json())
            .getAsJsonObject("data");
    }

    private static int status(TestGridFixtures.TestGrid grid, int plan, String query) {
        GetCraftingPlanTree request = new GetCraftingPlanTree();
        Map<String, String> path = new HashMap<>();
        path.put(
            "gridKey",
            TestGridFixtures.resolvedKey(grid)
                .toString());
        path.put("planId", Integer.toString(plan));
        if (!request.init(
            new AE2Controller.RequestContext(
                new TestGridFixtures.TestExchange(query),
                TestGridFixtures.principal(OWNER),
                path,
                null)))
            return request.finishResponse()
                .httpStatus();
        request.runOnServerThread(TestGridFixtures.ae(grid));
        return request.finishResponse()
            .httpStatus();
    }

    private static GetCraftingPlanTree request(TestGridFixtures.TestGrid grid, int plan, String query) {
        Map<String, String> path = new HashMap<>();
        path.put(
            "gridKey",
            TestGridFixtures.resolvedKey(grid)
                .toString());
        path.put("planId", Integer.toString(plan));
        GetCraftingPlanTree request = new GetCraftingPlanTree();
        assertTrue(
            request.init(
                new AE2Controller.RequestContext(
                    new TestGridFixtures.TestExchange(query),
                    TestGridFixtures.principal(OWNER),
                    path,
                    null)));
        return request;
    }

    private static JsonObject json(String value) {
        return new JsonParser().parse(value)
            .getAsJsonObject();
    }
}
