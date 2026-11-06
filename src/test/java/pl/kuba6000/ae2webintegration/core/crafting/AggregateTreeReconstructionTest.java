package pl.kuba6000.ae2webintegration.core.crafting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.Contribution;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.ContributionKind;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class AggregateTreeReconstructionTest {

    @Test
    void batchRemainderAndByproductSatisfyLaterRequestsBeforeTheNextProducer() {
        CraftingRecipeCapture.Builder source = capture("product", "part", "byproduct", "raw");
        source.result(0, 1, false);
        int product = source.addRecipe(1);
        input(source, product, 1, 2);
        input(source, product, 1, 3);
        input(source, product, 2, 2);
        source.addOutput(product, 0, 1, true);
        source.finishRecipe(product);
        int first = source.addRecipe(1);
        int rawGroup = source.addIngredient(first, 3);
        source.addCandidate(rawGroup, 3, 1);
        source.addOutput(first, 1, 3, true);
        source.addOutput(first, 1, 1, true);
        source.addOutput(first, 2, 2, false);
        source.finishRecipe(first);
        int second = source.addRecipe(1);
        input(source, second, 3, 5);
        source.addOutput(second, 1, 1, true);
        source.finishRecipe(second);
        source.addSummary(SummaryKind.INITIAL, 3, 8);

        CraftingTraceCapture trace = AggregateTreeReconstruction.from(source.build(), 100);
        assertNotNull(trace);
        CraftingTraceGraph graph = CraftingTraceGraph.from(trace);
        int productTask = task(graph, graph.root(), 0, 1);
        List<Integer> children = graph.taskRequests(productTask);
        int firstPart = trace.taskRequests()
            .get(children.get(0))
            .requestId();
        int secondPart = trace.taskRequests()
            .get(children.get(1))
            .requestId();
        int byproduct = trace.taskRequests()
            .get(children.get(2))
            .requestId();
        int firstTask = task(graph, firstPart, 0, 2);
        assertEquals(
            1,
            trace.tasks()
                .get(firstTask)
                .crafts());
        leaf(graph, secondPart, 0, ContributionKind.REUSED, 2, 1);
        int secondTask = task(graph, secondPart, 1, 1);
        assertEquals(
            1,
            trace.tasks()
                .get(secondTask)
                .crafts());
        leaf(graph, byproduct, 0, ContributionKind.REUSED, 2, 2);
        int firstRaw = trace.taskRequests()
            .get(
                graph.taskRequests(firstTask)
                    .get(0))
            .requestId();
        int secondRaw = trace.taskRequests()
            .get(
                graph.taskRequests(secondTask)
                    .get(0))
            .requestId();
        leaf(graph, firstRaw, 0, ContributionKind.STORAGE, 3, 3);
        leaf(graph, secondRaw, 0, ContributionKind.STORAGE, 5, 3);
    }

    @Test
    void rootRequestsNewOutputAndCanUseStoredBootstrapInput() {
        CraftingRecipeCapture.Builder source = capture("product");
        source.result(0, 1, false);
        int recipe = source.addRecipe(1);
        input(source, recipe, 0, 1);
        source.addOutput(recipe, 0, 1, true);
        source.finishRecipe(recipe);
        source.addSummary(SummaryKind.INITIAL, 0, 1);

        CraftingTraceCapture trace = AggregateTreeReconstruction.from(source.build(), 100);
        assertNotNull(trace);
        CraftingTraceGraph graph = CraftingTraceGraph.from(trace);
        int task = task(graph, graph.root(), 0, 1);
        int bootstrap = trace.taskRequests()
            .get(
                graph.taskRequests(task)
                    .get(0))
            .requestId();
        leaf(graph, bootstrap, 0, ContributionKind.STORAGE, 1, 0);
    }

    @Test
    void aSingleDemandCanUseStockEmissionCraftingAndMissingTogether() {
        CraftingRecipeCapture.Builder source = capture("product", "part");
        source.result(0, 1, true);
        int product = source.addRecipe(1);
        input(source, product, 1, 7);
        source.addOutput(product, 0, 1, true);
        source.finishRecipe(product);
        int part = source.addRecipe(3);
        source.addOutput(part, 1, 1, true);
        source.finishRecipe(part);
        source.addSummary(SummaryKind.INITIAL, 1, 1);
        source.addSummary(SummaryKind.EMITTED, 1, 1);
        source.addSummary(SummaryKind.MISSING, 1, 2);

        CraftingTraceCapture trace = AggregateTreeReconstruction.from(source.build(), 100);
        assertNotNull(trace);
        CraftingTraceGraph graph = CraftingTraceGraph.from(trace);
        int productTask = task(graph, graph.root(), 0, 1);
        int request = trace.taskRequests()
            .get(
                graph.taskRequests(productTask)
                    .get(0))
            .requestId();
        leaf(graph, request, 0, ContributionKind.STORAGE, 1, 1);
        leaf(graph, request, 1, ContributionKind.EMITTED, 1, 1);
        task(graph, request, 2, 3);
        leaf(graph, request, 3, ContributionKind.MISSING, 2, 1);
    }

    @Test
    void coproductGeneratedWhileARequestIsOpenSuppliesItsRemainingDemand() {
        CraftingRecipeCapture.Builder source = capture("product", "part", "component", "raw");
        source.result(0, 1, false);
        int product = source.addRecipe(1);
        input(source, product, 1, 3);
        source.addOutput(product, 0, 1, true);
        source.finishRecipe(product);
        int part = source.addRecipe(1);
        input(source, part, 2, 1);
        source.addOutput(part, 1, 1, true);
        source.finishRecipe(part);
        int component = source.addRecipe(1);
        input(source, component, 3, 1);
        source.addOutput(component, 2, 1, true);
        source.addOutput(component, 1, 2, false);
        source.finishRecipe(component);
        source.addSummary(SummaryKind.INITIAL, 3, 1);

        CraftingTraceCapture trace = AggregateTreeReconstruction.from(source.build(), 100);
        assertNotNull(trace);
        CraftingTraceGraph graph = CraftingTraceGraph.from(trace);
        int productTask = task(graph, graph.root(), 0, 1);
        int request = trace.taskRequests()
            .get(
                graph.taskRequests(productTask)
                    .get(0))
            .requestId();
        task(graph, request, 0, 1);
        leaf(graph, request, 1, ContributionKind.REUSED, 2, 1);
    }

    @Test
    void incompleteAmbiguousOverflowingOrUnaccountedCapturesCannotBeReconstructed() {
        CraftingRecipeCapture.Builder source = capture("product", "input");
        source.result(0, 1, false);
        int recipe = source.addRecipe(1);
        input(source, recipe, 1, 1);
        source.addOutput(recipe, 0, 1, true);
        assertNull(AggregateTreeReconstruction.from(source.build(), 100));
        source.finishRecipe(recipe);
        source.addSummary(SummaryKind.INITIAL, 1, 1);
        CraftingRecipeCapture complete = source.build();
        assertNull(AggregateTreeReconstruction.from(complete, 9));
        assertNotNull(AggregateTreeReconstruction.from(complete, 10));
        source.addSummary(SummaryKind.INITIAL, 1, 1);
        assertNull(AggregateTreeReconstruction.from(source.build(), 100));

        CraftingRecipeCapture.Builder ambiguous = capture("product", "first", "second");
        ambiguous.result(0, 1, false);
        int first = ambiguous.addRecipe(1);
        int alternatives = ambiguous.addIngredient(first, 1);
        ambiguous.addCandidate(alternatives, 1, 1);
        ambiguous.addCandidate(alternatives, 2, 1);
        ambiguous.addOutput(first, 0, 1, true);
        ambiguous.finishRecipe(first);
        assertNull(AggregateTreeReconstruction.from(ambiguous.build(), 100));

        CraftingRecipeCapture.Builder overflow = capture("product", "input");
        overflow.result(0, 1, false);
        int overflowRecipe = overflow.addRecipe(1);
        int oversized = overflow.addIngredient(overflowRecipe, 2);
        overflow.addCandidate(oversized, 1, Long.MAX_VALUE);
        overflow.addOutput(overflowRecipe, 0, 1, true);
        overflow.finishRecipe(overflowRecipe);
        assertNull(AggregateTreeReconstruction.from(overflow.build(), 100));

        CraftingRecipeCapture.Builder cycle = capture("product");
        cycle.result(0, 1, false);
        int recursive = cycle.addRecipe(2);
        input(cycle, recursive, 0, 1);
        cycle.addOutput(recursive, 0, 1, true);
        cycle.finishRecipe(recursive);
        assertNull(AggregateTreeReconstruction.from(cycle.build(), 100));
    }

    @Test
    void quantitiesAboveJavascriptSafeIntegerAndAtLongLimitRemainExact() {
        for (long amount : new long[] { 9007199254740993L, Long.MAX_VALUE }) {
            CraftingRecipeCapture.Builder source = capture("product", "input");
            source.result(0, amount, false);
            int recipe = source.addRecipe(amount);
            input(source, recipe, 1, 1);
            source.addOutput(recipe, 0, 1, true);
            source.finishRecipe(recipe);
            source.addSummary(SummaryKind.INITIAL, 1, amount);
            CraftingTraceCapture trace = AggregateTreeReconstruction.from(source.build(), 100);
            assertNotNull(trace);
            assertEquals(
                amount,
                trace.requests()
                    .get(0)
                    .amount());
            assertEquals(
                amount,
                trace.tasks()
                    .get(0)
                    .crafts());
            assertEquals(
                amount,
                trace.requests()
                    .get(1)
                    .amount());
        }
    }

    @Test
    void deepCompleteCaptureIsTraversedWithoutJavaRecursion() {
        int depth = 2500;
        CraftingRecipeCapture.Builder source = new CraftingRecipeCapture.Builder(20_000, 100_000);
        for (int i = 0; i <= depth; i++) source.addResource(() -> TraceFixtures.descriptor("item"));
        source.result(0, 1, false);
        for (int i = 0; i < depth; i++) {
            int recipe = source.addRecipe(1);
            input(source, recipe, i + 1, 1);
            source.addOutput(recipe, i, 1, true);
            source.finishRecipe(recipe);
        }
        source.addSummary(SummaryKind.INITIAL, depth, 1);
        CraftingTraceCapture trace = AggregateTreeReconstruction.from(source.build(), 20_000);
        assertNotNull(trace);
        assertEquals(
            depth,
            trace.tasks()
                .size());
        assertEquals(
            depth + 1,
            trace.requests()
                .size());
    }

    private static CraftingRecipeCapture.Builder capture(String... names) {
        CraftingRecipeCapture.Builder source = new CraftingRecipeCapture.Builder(100, 1000);
        for (String name : names) source.addResource(() -> TraceFixtures.descriptor(name));
        return source;
    }

    private static void input(CraftingRecipeCapture.Builder source, int recipe, int resource, long amount) {
        int group = source.addIngredient(recipe, 1);
        source.addCandidate(group, resource, amount);
    }

    private static int task(CraftingTraceGraph graph, int request, int index, long amount) {
        Contribution contribution = graph.capture()
            .contributions()
            .get(
                graph.contributions(request)
                    .get(index));
        assertEquals(ContributionKind.CRAFT, contribution.kind());
        assertEquals(amount, contribution.amount());
        assertNotNull(contribution.task());
        return contribution.task();
    }

    private static void leaf(CraftingTraceGraph graph, int request, int index, ContributionKind kind, long amount,
        int resource) {
        Contribution contribution = graph.capture()
            .contributions()
            .get(
                graph.contributions(request)
                    .get(index));
        assertEquals(kind, contribution.kind());
        assertEquals(amount, contribution.amount());
        assertEquals(resource, contribution.resourceId());
    }
}
