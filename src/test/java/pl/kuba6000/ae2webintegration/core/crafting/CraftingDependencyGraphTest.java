package pl.kuba6000.ae2webintegration.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingDependencyGraphTest {

    @Test
    void navigatesDeclaredRecipeGroupsWithoutChoosingAnAlternative() {
        CraftingRecipeCapture.Builder builder = new CraftingRecipeCapture.Builder(30, 1000);
        int product = resource(builder, "product");
        int water = resource(builder, "water");
        int bucket = resource(builder, "bucket");
        builder.result(product, 4, false);
        int recipe = builder.addRecipe(4);
        int ingredient = builder.addIngredient(recipe, 2);
        builder.addCandidate(ingredient, water, 1000);
        builder.addCandidate(ingredient, bucket, 1);
        builder.addOutput(recipe, product, 1, true);
        builder.finishRecipe(recipe);
        CraftingRecipeCapture capture = builder.build();

        CraftingDependencyGraph graph = CraftingDependencyGraph.from(capture);
        assertSame(capture, graph.capture());
        CraftingRecipeCapture.Amount root = graph.root();
        assertNotNull(root);
        assertEquals(product, root.resourceId());
        assertEquals(4, root.amount());
        assertEquals(Collections.singletonList(0), graph.declaredProducers(product));
        assertEquals(Collections.singletonList(ingredient), graph.ingredients(recipe));
        assertEquals(Arrays.asList(0, 1), graph.alternatives(ingredient));
        assertEquals(Collections.singletonList(0), graph.outputs(recipe));
        assertEquals(Collections.emptyList(), graph.declaredProducers(water));
        assertEquals(
            1000,
            graph.capture()
                .candidates()
                .get(0)
                .amount());
        assertEquals(
            1,
            graph.capture()
                .candidates()
                .get(1)
                .amount());
    }

    @Test
    void sharedAndCyclicDependenciesReuseReferencesAndLeaveUnmatchedOperationsVisible() {
        CraftingRecipeCapture.Builder builder = new CraftingRecipeCapture.Builder(50, 1000);
        int a = resource(builder, "a");
        int b = resource(builder, "b");
        int c = resource(builder, "c");
        int unrelated = resource(builder, "c");
        builder.result(a, 1, false);
        int makeA = recipe(builder, a, b, c);
        int makeB = recipe(builder, b, c);
        int makeC = recipe(builder, c, a);
        int unplaced = recipe(builder, unrelated);

        CraftingDependencyGraph graph = CraftingDependencyGraph.from(builder.build());
        assertEquals(Collections.singletonList(unplaced), graph.unplacedRecipes());
        assertEquals(Collections.singletonList(2), graph.declaredProducers(c));
        assertEquals(
            makeC,
            graph.capture()
                .outputs()
                .get(2)
                .recipeId());
        assertEquals(
            c,
            graph.capture()
                .candidates()
                .get(
                    graph.alternatives(
                        graph.ingredients(makeA)
                            .get(1))
                        .get(0))
                .resourceId());
        assertEquals(
            c,
            graph.capture()
                .candidates()
                .get(
                    graph.alternatives(
                        graph.ingredients(makeB)
                            .get(0))
                        .get(0))
                .resourceId());
        assertEquals(
            a,
            graph.capture()
                .candidates()
                .get(
                    graph.alternatives(
                        graph.ingredients(makeC)
                            .get(0))
                        .get(0))
                .resourceId());
        assertEquals(
            4,
            graph.capture()
                .recipes()
                .size());
        assertEquals(Collections.singletonList(3), graph.declaredProducers(unrelated));
    }

    @Test
    void partialCaptureKeepsItsLimitsAndUnknownRelationships() {
        CraftingRecipeCapture.Builder builder = new CraftingRecipeCapture.Builder(5, 1000);
        int product = resource(builder, "product");
        builder.result(product, 2, true);
        int recipe = builder.addRecipe(2);
        int ingredient = builder.addIngredient(recipe, 1);
        int input = resource(builder, "input");
        builder.addCandidate(ingredient, input, 1);
        assertFalse(builder.addOutput(recipe, product, 1, null));
        CraftingRecipeCapture capture = builder.build();

        CraftingDependencyGraph graph = CraftingDependencyGraph.from(capture);
        assertSame(capture, graph.capture());
        assertTrue(
            graph.capture()
                .simulated());
        assertEquals(
            Collections.singleton(CraftingRecipeCapture.Limit.ENTRIES),
            graph.capture()
                .limits());
        assertFalse(
            graph.capture()
                .recipes()
                .get(recipe)
                .complete());
        assertEquals(Collections.singletonList(recipe), graph.unplacedRecipes());
        assertTrue(
            graph.declaredProducers(product)
                .isEmpty());
        assertEquals(Collections.singletonList(0), graph.alternatives(ingredient));
        assertTrue(
            graph.declaredProducers(input)
                .isEmpty());
        assertTrue(
            graph.capture()
                .summaries()
                .isEmpty());
    }

    @Test
    void absentRootKeepsAllOperationsAndAdjacencyCannotBeMutated() {
        CraftingRecipeCapture.Builder builder = new CraftingRecipeCapture.Builder(20, 1000);
        int product = resource(builder, "product");
        int input = resource(builder, "input");
        int recipe = recipe(builder, product, input);
        CraftingDependencyGraph graph = CraftingDependencyGraph.from(builder.build());
        assertNull(graph.root());
        assertEquals(Collections.singletonList(recipe), graph.unplacedRecipes());
        assertThrows(
            UnsupportedOperationException.class,
            () -> graph.unplacedRecipes()
                .clear());
        assertThrows(
            UnsupportedOperationException.class,
            () -> graph.declaredProducers(product)
                .set(0, 5));
        assertThrows(
            UnsupportedOperationException.class,
            () -> graph.ingredients(recipe)
                .clear());
        assertThrows(
            UnsupportedOperationException.class,
            () -> graph.alternatives(0)
                .clear());
        assertThrows(
            UnsupportedOperationException.class,
            () -> graph.outputs(recipe)
                .clear());
        assertThrows(
            IndexOutOfBoundsException.class,
            () -> graph.outputs(recipe)
                .get(1));
        assertThrows(
            IndexOutOfBoundsException.class,
            () -> graph.outputs(recipe)
                .get(-1));
        assertEquals(Collections.singletonList(0), graph.outputs(recipe));
    }

    @Test
    void followsADeepChainWithoutRecursivelyExpandingIt() {
        int depth = 10000;
        CraftingRecipeCapture.Builder builder = new CraftingRecipeCapture.Builder(60000, 100000);
        int result = resource(builder, "node");
        builder.result(result, 1, false);
        int output = result;
        for (int i = 0; i < depth; i++) {
            int input = resource(builder, "node");
            recipe(builder, output, input);
            output = input;
        }
        CraftingDependencyGraph graph = CraftingDependencyGraph.from(builder.build());
        assertTrue(
            graph.unplacedRecipes()
                .isEmpty());
        assertEquals(
            depth,
            graph.capture()
                .recipes()
                .size());
        assertTrue(
            graph.declaredProducers(output)
                .isEmpty());
    }

    private static int recipe(CraftingRecipeCapture.Builder builder, int output, int... inputs) {
        int recipe = builder.addRecipe(1);
        for (int input : inputs) {
            int group = builder.addIngredient(recipe, 1);
            builder.addCandidate(group, input, 1);
        }
        builder.addOutput(recipe, output, 1, true);
        builder.finishRecipe(recipe);
        return recipe;
    }

    private static int resource(CraftingRecipeCapture.Builder builder, String name) {
        return builder.addResource(() -> new ResourceDescriptor(null, null, 0, 0, name, ResourceType.ITEM, null, null));
    }
}
