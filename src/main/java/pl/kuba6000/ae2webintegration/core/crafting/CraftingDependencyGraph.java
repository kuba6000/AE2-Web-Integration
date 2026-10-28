package pl.kuba6000.ae2webintegration.core.crafting;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.IntUnaryOperator;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resource-mediated navigation through captured recipe declarations. Local capture IDs remain the
 * node references; a resource shared by several recipes or a cycle is never expanded into copies.
 * These links describe possible dependencies, not selected ingredients or observed material transfers.
 */
public final class CraftingDependencyGraph {

    private final CraftingRecipeCapture capture;
    private final Adjacency producers;
    private final Adjacency ingredients;
    private final Adjacency alternatives;
    private final Adjacency outputs;
    private final List<Integer> unplacedRecipes;

    private CraftingDependencyGraph(CraftingRecipeCapture capture) {
        this.capture = capture;
        producers = new Adjacency(
            capture.resources()
                .size(),
            capture.outputs()
                .size(),
            row -> capture.outputs()
                .get(row)
                .resourceId());
        ingredients = new Adjacency(
            capture.recipes()
                .size(),
            capture.ingredients()
                .size(),
            row -> capture.ingredients()
                .get(row)
                .recipeId());
        alternatives = new Adjacency(
            capture.ingredients()
                .size(),
            capture.candidates()
                .size(),
            row -> capture.candidates()
                .get(row)
                .ingredientId());
        outputs = new Adjacency(
            capture.recipes()
                .size(),
            capture.outputs()
                .size(),
            row -> capture.outputs()
                .get(row)
                .recipeId());
        unplacedRecipes = findUnplacedRecipes();
    }

    /**
     * Index a detached capture with valid local references. Time and retained index size are linear
     * in its already bounded tables. No descriptor, recipe, quantity or candidate table is copied.
     */
    public static @NotNull CraftingDependencyGraph from(@NotNull CraftingRecipeCapture capture) {
        return new CraftingDependencyGraph(capture);
    }

    /** Source declarations, quantities and incompleteness remain authoritative and unchanged. */
    public @NotNull CraftingRecipeCapture capture() {
        return capture;
    }

    /** Null when capture did not retain the calculated result. */
    public @Nullable CraftingRecipeCapture.Amount root() {
        return capture.result();
    }

    /**
     * Output-row IDs declaring this exact local resource. They are potential producers, not proof
     * that any particular recipe supplied a consumer; fuzzy alternatives may be absent from capture.
     */
    public @NotNull List<Integer> declaredProducers(int resourceId) {
        return producers.rowsFor(resourceId);
    }

    /** Ingredient-group IDs declared by the recipe. */
    public @NotNull List<Integer> ingredients(int recipeId) {
        return ingredients.rowsFor(recipeId);
    }

    /** Candidate-row IDs in one declared alternative group; none is marked selected. */
    public @NotNull List<Integer> alternatives(int ingredientId) {
        return alternatives.rowsFor(ingredientId);
    }

    /** Output-row IDs declared by the recipe, including captured byproducts. */
    public @NotNull List<Integer> outputs(int recipeId) {
        return outputs.rowsFor(recipeId);
    }

    /**
     * Selected recipe IDs not reachable from the root through captured exact declared identities.
     * These are not unused recipes: fuzzy matches, missing root data or truncation can leave actual
     * dependencies unplaced. All their captured declarations remain navigable.
     */
    public @NotNull List<Integer> unplacedRecipes() {
        return unplacedRecipes;
    }

    private List<Integer> findUnplacedRecipes() {
        boolean[] seenResources = new boolean[capture.resources()
            .size()];
        boolean[] seenRecipes = new boolean[capture.recipes()
            .size()];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        CraftingRecipeCapture.Amount root = root();
        if (root != null) {
            pending.add(root.resourceId());
            seenResources[root.resourceId()] = true;
        }
        while (!pending.isEmpty()) {
            int resource = pending.removeFirst();
            for (int output : declaredProducers(resource)) {
                int recipe = capture.outputs()
                    .get(output)
                    .recipeId();
                if (seenRecipes[recipe]) continue;
                seenRecipes[recipe] = true;
                for (int ingredient : ingredients(recipe)) {
                    for (int alternative : alternatives(ingredient)) {
                        int input = capture.candidates()
                            .get(alternative)
                            .resourceId();
                        if (!seenResources[input]) {
                            seenResources[input] = true;
                            pending.addLast(input);
                        }
                    }
                }
            }
        }
        int[] unplaced = new int[seenRecipes.length];
        int count = 0;
        for (int recipe = 0; recipe < seenRecipes.length; recipe++) {
            if (!seenRecipes[recipe]) unplaced[count++] = recipe;
        }
        return new RowIds(unplaced, 0, count);
    }

    /** Stores one row ID per relationship, with contiguous immutable ranges for each source. */
    private static final class Adjacency {

        private final int[] offsets;
        private final int[] rows;

        private Adjacency(int sources, int count, IntUnaryOperator sourceOf) {
            offsets = new int[sources + 1];
            rows = new int[count];
            for (int row = 0; row < count; row++) offsets[sourceOf.applyAsInt(row) + 1]++;
            for (int source = 1; source < offsets.length; source++) offsets[source] += offsets[source - 1];
            int[] next = offsets.clone();
            for (int row = 0; row < count; row++) rows[next[sourceOf.applyAsInt(row)]++] = row;
        }

        private List<Integer> rowsFor(int source) {
            return new RowIds(rows, offsets[source], offsets[source + 1]);
        }
    }

    /** A view avoids copying a high-degree neighborhood on every expansion. */
    private static final class RowIds extends AbstractList<Integer> implements RandomAccess {

        private final int[] rows;
        private final int start;
        private final int end;

        private RowIds(int[] rows, int start, int end) {
            this.rows = rows;
            this.start = start;
            this.end = end;
        }

        @Override
        public Integer get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("Adjacency index out of range");
            return rows[start + index];
        }

        @Override
        public int size() {
            return end - start;
        }
    }
}
