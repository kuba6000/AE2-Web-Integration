package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.ContributionKind;
import pl.kuba6000.ae2webintegration.core.crafting.TraceFixtures;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingPlanDocumentTest extends GridTestScope {

    @AfterEach
    void clearPlans() {
        GridData.clearRuntimeState();
    }

    @Test
    void wholeAggregateDocumentSharesDeclarationsAndMarksMissingEdges() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(600);
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture
                    .completedFuture(CraftingPlanTreePreparationTest.job(CraftingPlanDocumentTest::aggregate)),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        JsonObject data = read(grid, plan);
        assertEquals(
            "READY",
            data.get("state")
                .getAsString());
        assertEquals(
            "AGGREGATE",
            data.get("mode")
                .getAsString());
        assertEquals(
            "9007199254740993",
            data.getAsJsonObject("root")
                .get("quantity")
                .getAsString());
        assertEquals(
            2,
            data.getAsJsonArray("recipes")
                .size());
        assertEquals(
            5,
            data.getAsJsonArray("resources")
                .size());
        assertEquals(
            2,
            data.get("missingResourceCount")
                .getAsInt());
        Map<Integer, JsonObject> nodes = nodes(data);
        JsonObject root = nodes.get(
            data.getAsJsonArray("roots")
                .get(0)
                .getAsInt());
        assertEquals(
            "RESOURCE",
            root.get("kind")
                .getAsString());
        JsonArray producers = root.getAsJsonArray("children");
        assertEquals(3, producers.size());
        JsonObject first = nodes.get(
            producers.get(0)
                .getAsJsonObject()
                .get("nodeId")
                .getAsInt());
        JsonObject second = nodes.get(
            producers.get(1)
                .getAsJsonObject()
                .get("nodeId")
                .getAsInt());
        JsonObject third = nodes.get(
            producers.get(2)
                .getAsJsonObject()
                .get("nodeId")
                .getAsInt());
        assertEquals(first.get("recipeId"), second.get("recipeId"));
        assertNotEquals(first.get("recipeId"), third.get("recipeId"));
        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(
            "2",
            first.get("crafts")
                .getAsString());
        assertEquals(
            "7",
            second.get("crafts")
                .getAsString());
        assertTrue(
            producers.get(0)
                .getAsJsonObject()
                .get("missing")
                .getAsBoolean());
        assertTrue(
            producers.get(1)
                .getAsJsonObject()
                .get("missing")
                .getAsBoolean());
        assertFalse(
            producers.get(2)
                .getAsJsonObject()
                .get("missing")
                .getAsBoolean());
        JsonObject recipe = data.getAsJsonArray("recipes")
            .get(
                first.get("recipeId")
                    .getAsInt())
            .getAsJsonObject();
        JsonObject group = recipe.getAsJsonArray("inputs")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "3",
            group.get("multiplier")
                .getAsString());
        assertEquals(
            "4",
            group.getAsJsonArray("candidates")
                .get(0)
                .getAsJsonObject()
                .get("quantity")
                .getAsString());
        assertEquals(
            "1",
            recipe.getAsJsonArray("outputs")
                .get(0)
                .getAsJsonObject()
                .get("quantity")
                .getAsString());
        assertEquals(
            "UNLINKED_MISSING",
            nodes.get(
                data.getAsJsonArray("roots")
                    .get(1)
                    .getAsInt())
                .get("kind")
                .getAsString());
        for (JsonObject node : nodes.values()) for (JsonElement child : node.getAsJsonArray("children")) {
            JsonObject edge = child.getAsJsonObject();
            assertTrue(
                nodes.containsKey(
                    edge.get("nodeId")
                        .getAsInt()));
            assertFalse(edge.has("node"));
        }
    }

    @Test
    void aggregateAllocatesStockBeforeCraftingAndMarksOnlyTheShortagePath() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(603);
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture
                    .completedFuture(CraftingPlanTreePreparationTest.job(CraftingPlanDocumentTest::shared)),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        JsonObject data = read(grid, plan);
        assertEquals(
            "AGGREGATE",
            data.get("mode")
                .getAsString());
        Map<Integer, JsonObject> nodes = nodes(data);
        JsonObject root = nodes.get(
            data.getAsJsonArray("roots")
                .get(0)
                .getAsInt());
        assertEquals(
            "REQUEST",
            root.get("kind")
                .getAsString());
        JsonObject task = childNode(nodes, root, 0);
        JsonArray requests = task.getAsJsonArray("children");
        assertFalse(
            requests.get(0)
                .getAsJsonObject()
                .get("missing")
                .getAsBoolean());
        assertTrue(
            requests.get(1)
                .getAsJsonObject()
                .get("missing")
                .getAsBoolean());
        JsonObject first = childNode(nodes, task, 0);
        JsonObject second = childNode(nodes, task, 1);
        assertEquals(
            1,
            first.getAsJsonArray("children")
                .size());
        assertContribution(nodes, first, 0, "STORAGE", "6", 1);
        assertContribution(nodes, second, 0, "STORAGE", "2", 1);
        JsonObject craft = second.getAsJsonArray("children")
            .get(1)
            .getAsJsonObject();
        assertEquals(
            "4",
            craft.get("quantity")
                .getAsString());
        JsonObject inner = childNode(nodes, childNode(nodes, second, 1), 0);
        assertContribution(nodes, inner, 0, "MISSING", "8", 2);
        assertEquals(data, read(grid, plan));
    }

    @Test
    void ambiguousAggregateKeepsEveryDeclaredAlternativeAndMissingSummary() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(604);
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture
                    .completedFuture(CraftingPlanTreePreparationTest.job(CraftingPlanDocumentTest::ambiguous)),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        JsonObject data = read(grid, plan);
        assertEquals(
            "AGGREGATE",
            data.get("mode")
                .getAsString());
        assertEquals(
            1,
            data.get("missingResourceCount")
                .getAsInt());
        Map<Integer, JsonObject> nodes = nodes(data);
        JsonObject root = nodes.get(
            data.getAsJsonArray("roots")
                .get(0)
                .getAsInt());
        assertEquals(
            "RESOURCE",
            root.get("kind")
                .getAsString());
        JsonObject group = childNode(nodes, childNode(nodes, root, 0), 0);
        assertEquals(
            "INGREDIENT",
            group.get("kind")
                .getAsString());
        assertEquals(
            2,
            group.getAsJsonArray("children")
                .size());
        assertEquals(
            1,
            childNode(nodes, group, 0).get("resourceId")
                .getAsInt());
        assertEquals(
            2,
            childNode(nodes, group, 1).get("resourceId")
                .getAsInt());
        assertEquals(
            "1",
            data.getAsJsonArray("resources")
                .get(1)
                .getAsJsonObject()
                .get("missingAmount")
                .getAsString());
    }

    @Test
    void overflowingMissingRootKeepsItsShortageIdentityWithAnUnknownAmount() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(609);
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(20, 1000);
        int resource = capture.addResource(
            () -> TestGridFixtures.planOutput()
                .description());
        capture.result(resource, 1, true);
        capture.addSummary(SummaryKind.MISSING, resource, Long.MAX_VALUE);
        capture.addSummary(SummaryKind.MISSING, resource, 1);
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(capture::build)),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        JsonObject data = read(grid, plan);
        JsonObject descriptor = data.getAsJsonArray("resources")
            .get(0)
            .getAsJsonObject();
        assertTrue(
            descriptor.get("missing")
                .getAsBoolean());
        assertTrue(
            descriptor.get("missingAmount")
                .isJsonNull());
        assertFalse(
            data.get("missingComplete")
                .getAsBoolean());
        assertEquals(
            1,
            data.get("missingResourceCount")
                .getAsInt());
    }

    @Test
    void traceKeepsActualSourcesSharedTasksAndPerConnectionAmounts() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(601);
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture
                    .completedFuture(CraftingPlanTreePreparationTest.traceJob(CraftingPlanDocumentTest::trace, () -> {
                        throw new AssertionError("Unexpected aggregate");
                    })),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        JsonObject data = read(grid, plan);
        assertEquals(
            "TRACE",
            data.get("mode")
                .getAsString());
        Map<Integer, JsonObject> nodes = nodes(data);
        JsonObject root = nodes.get(
            data.getAsJsonArray("roots")
                .get(0)
                .getAsInt());
        JsonArray links = root.getAsJsonArray("children");
        JsonObject actual = nodes.get(
            links.get(0)
                .getAsJsonObject()
                .get("nodeId")
                .getAsInt());
        JsonObject unknown = nodes.get(
            links.get(1)
                .getAsJsonObject()
                .get("nodeId")
                .getAsInt());
        assertEquals(
            1,
            actual.get("resourceId")
                .getAsInt());
        assertTrue(
            unknown.get("resourceId")
                .isJsonNull());
        assertFalse(
            links.get(0)
                .getAsJsonObject()
                .get("missing")
                .getAsBoolean());
        JsonObject craft = links.get(2)
            .getAsJsonObject();
        assertEquals(
            "7",
            craft.get("quantity")
                .getAsString());
        assertTrue(
            craft.get("missing")
                .getAsBoolean());
        JsonObject task = nodes.get(
            craft.get("nodeId")
                .getAsInt());
        JsonObject recipe = data.getAsJsonArray("recipes")
            .get(
                task.get("recipeId")
                    .getAsInt())
            .getAsJsonObject();
        JsonObject input = recipe.getAsJsonArray("inputs")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "1",
            input.get("multiplier")
                .getAsString());
        assertEquals(
            "2",
            input.getAsJsonArray("candidates")
                .get(0)
                .getAsJsonObject()
                .get("quantity")
                .getAsString());
        JsonArray requests = task.getAsJsonArray("children");
        assertTrue(
            requests.get(0)
                .getAsJsonObject()
                .get("missing")
                .getAsBoolean());
        JsonObject cycle = nodes.get(
            requests.get(1)
                .getAsJsonObject()
                .get("nodeId")
                .getAsInt());
        JsonObject shared = cycle.getAsJsonArray("children")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "3",
            shared.get("quantity")
                .getAsString());
        assertEquals(craft.get("nodeId"), shared.get("nodeId"));
        assertEquals(
            1,
            data.getAsJsonArray("recipes")
                .size());
    }

    @Test
    void largeDocumentRemainsReadable() {
        JsonObject data = documentWithResources(100_000);
        assertEquals(
            "READY",
            data.get("state")
                .getAsString());
        assertEquals(
            100_000,
            data.getAsJsonArray("resources")
                .size());
    }

    private JsonObject documentWithResources(int count) {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(602);
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(() -> {
                CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(count, 2 * 1024 * 1024);
                for (int i = 0; i < count; i++) capture.addResource(() -> TraceFixtures.descriptor("x"));
                capture.result(0, 1, false);
                return capture.build();
            })), TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        return read(grid, plan);
    }

    @Test
    void oversizedDocumentReturnsSmallExplicitState() {
        JsonObject data = documentWithResources(500_000);
        assertEquals(
            "TOO_LARGE",
            data.get("state")
                .getAsString());
        assertEquals(
            0,
            data.getAsJsonArray("nodes")
                .size());
        assertEquals(
            0,
            data.getAsJsonArray("resources")
                .size());
        assertTrue(
            data.get("captureId")
                .isJsonPrimitive());
    }

    private static CraftingTraceCapture trace() {
        CraftingTraceCapture.Builder capture = new CraftingTraceCapture.Builder(50, 1000);
        for (String name : new String[] { "requested", "substitute", "missing" })
            capture.addResource(() -> TraceFixtures.descriptor(name));
        int request = capture.addRequest(0, 10);
        capture.root(request, false);
        capture.addContribution(request, ContributionKind.STORAGE, 2, 1);
        capture.addContribution(request, ContributionKind.STORAGE, 1);
        int task = capture.addTask(3);
        capture.addCraft(request, 7, task);
        capture.addInput(task, 2, 2);
        capture.addOutput(task, 0, 1, true);
        int missing = capture.addRequest(2, 6);
        capture.addChildRequest(task, missing);
        int cycle = capture.addRequest(0, 3);
        capture.addChildRequest(task, cycle);
        capture.finishTask(task);
        capture.addContribution(missing, ContributionKind.MISSING, 6);
        capture.addCraft(cycle, 3, task);
        return capture.build();
    }

    private static CraftingRecipeCapture aggregate() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(100, 1000);
        for (int i = 0; i < 5; i++) capture.addResource(
            () -> TestGridFixtures.planOutput()
                .description());
        capture.result(0, 9007199254740993L, false);
        for (int i = 0; i < 3; i++) {
            int recipe = capture.addRecipe(i == 0 ? 2 : 7);
            int input = capture.addIngredient(recipe, 3);
            capture.addCandidate(input, i == 2 ? 2 : 1, 4);
            capture.addOutput(recipe, 0, 1, true);
            capture.finishRecipe(recipe);
        }
        capture.addSummary(SummaryKind.MISSING, 1, 5);
        capture.addSummary(SummaryKind.MISSING, 4, 6);
        return capture.build();
    }

    private static CraftingRecipeCapture shared() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(100, 1000);
        for (String name : new String[] { "product", "shared", "raw" })
            capture.addResource(() -> TraceFixtures.descriptor(name));
        capture.result(0, 1, true);
        int product = capture.addRecipe(1);
        for (int i = 0; i < 2; i++) {
            int group = capture.addIngredient(product, 1);
            capture.addCandidate(group, 1, 6);
        }
        capture.addOutput(product, 0, 1, true);
        capture.finishRecipe(product);
        int intermediate = capture.addRecipe(4);
        int input = capture.addIngredient(intermediate, 1);
        capture.addCandidate(input, 2, 2);
        capture.addOutput(intermediate, 1, 1, true);
        capture.finishRecipe(intermediate);
        capture.addSummary(SummaryKind.INITIAL, 1, 8);
        capture.addSummary(SummaryKind.MISSING, 2, 8);
        return capture.build();
    }

    private static CraftingRecipeCapture ambiguous() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(100, 1000);
        for (String name : new String[] { "product", "first", "second" })
            capture.addResource(() -> TraceFixtures.descriptor(name));
        capture.result(0, 1, true);
        int recipe = capture.addRecipe(1);
        int group = capture.addIngredient(recipe, 1);
        capture.addCandidate(group, 1, 1);
        capture.addCandidate(group, 2, 1);
        capture.addOutput(recipe, 0, 1, true);
        capture.finishRecipe(recipe);
        capture.addSummary(SummaryKind.MISSING, 1, 1);
        return capture.build();
    }

    private static JsonObject childNode(Map<Integer, JsonObject> nodes, JsonObject parent, int index) {
        return nodes.get(
            parent.getAsJsonArray("children")
                .get(index)
                .getAsJsonObject()
                .get("nodeId")
                .getAsInt());
    }

    private static void assertContribution(Map<Integer, JsonObject> nodes, JsonObject request, int index, String kind,
        String quantity, int resource) {
        JsonObject edge = request.getAsJsonArray("children")
            .get(index)
            .getAsJsonObject();
        assertEquals(
            quantity,
            edge.get("quantity")
                .getAsString());
        JsonObject contribution = childNode(nodes, request, index);
        assertEquals(
            kind,
            contribution.get("contribution")
                .getAsString());
        assertEquals(
            resource,
            contribution.get("resourceId")
                .getAsInt());
    }

    private static Map<Integer, JsonObject> nodes(JsonObject data) {
        Map<Integer, JsonObject> nodes = new HashMap<>();
        for (JsonElement element : data.getAsJsonArray("nodes")) {
            JsonObject node = element.getAsJsonObject();
            assertNull(
                nodes.put(
                    node.get("id")
                        .getAsInt(),
                    node));
        }
        return nodes;
    }

    private static JsonObject read(TestGridFixtures.TestGrid grid, int plan) {
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
                    new TestGridFixtures.TestExchange(""),
                    TestGridFixtures.principal(7),
                    path,
                    null)));
        request.runOnServerThread(TestGridFixtures.ae(grid));
        return new JsonParser().parse(
            request.finishResponse()
                .json())
            .getAsJsonObject()
            .getAsJsonObject("data");
    }
}
