package pl.kuba6000.ae2webintegration.core.crafting;

import java.math.BigInteger;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.IntPredicate;
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
    private final MissingIndex missing;

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
        boolean[] reachableResources = new boolean[capture.resources()
            .size()];
        unplacedRecipes = findUnplacedRecipes(reachableResources);
        missing = new MissingIndex(reachableResources);
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

    /** Captured plan-level missing quantity, never a per-branch allocation. Null means unknown absence. */
    public @Nullable BigInteger missingAmount(int resourceId) {
        BigInteger amount = missing.amounts[resourceId];
        return amount == null && missing.complete ? BigInteger.ZERO : amount;
    }

    /** Whether missing absence can be established from this fully captured plan. */
    public boolean missingComplete() {
        return missing.complete;
    }

    /** Number of distinct resources with a captured positive missing amount. */
    public int missingResourceCount() {
        return missing.count;
    }

    /** Missing resources with no captured dependency path from the calculated target. */
    public @NotNull List<Integer> unlinkedMissingResources() {
        return missing.unlinked;
    }

    public @NotNull List<Integer> declaredProducers(int resourceId, boolean missingOnly) {
        return missingOnly ? missing.producers.rowsFor(resourceId) : declaredProducers(resourceId);
    }

    public @NotNull List<Integer> ingredients(int recipeId, boolean missingOnly) {
        return missingOnly ? missing.ingredients.rowsFor(recipeId) : ingredients(recipeId);
    }

    public @NotNull List<Integer> alternatives(int ingredientId, boolean missingOnly) {
        return missingOnly ? missing.alternatives.rowsFor(ingredientId) : alternatives(ingredientId);
    }

    public @NotNull List<Integer> unplacedRecipes(boolean missingOnly) {
        return missingOnly ? missing.unplaced : unplacedRecipes;
    }

    /** Reverse only dependency links, never the presentation links from recipes back to outputs. */
    private final class MissingIndex {

        private final BigInteger[] amounts = new BigInteger[capture.resources()
            .size()];
        private final boolean complete;
        private final int count;
        private final List<Integer> unlinked;
        private final List<Integer> unplaced;
        private final Adjacency producers;
        private final Adjacency ingredients;
        private final Adjacency alternatives;

        private MissingIndex(boolean[] reachableResources) {
            boolean allCaptured = capture.result() != null && capture.limits()
                .isEmpty();
            for (CraftingRecipeCapture.Recipe recipe : capture.recipes()) allCaptured &= recipe.complete();
            complete = allCaptured;
            for (CraftingRecipeCapture.Summary summary : capture.summaries()) {
                if (summary.kind() != CraftingRecipeCapture.SummaryKind.MISSING) continue;
                if (summary.amount() < 0) throw new IllegalArgumentException("Negative missing quantity");
                BigInteger previous = amounts[summary.resourceId()];
                BigInteger amount = BigInteger.valueOf(summary.amount());
                amounts[summary.resourceId()] = previous == null ? amount : previous.add(amount);
            }
            boolean[] relevantResources = new boolean[amounts.length];
            boolean[] relevantRecipes = new boolean[capture.recipes()
                .size()];
            boolean[] relevantGroups = new boolean[capture.ingredients()
                .size()];
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            int knownMissing = 0;
            for (int resource = 0; resource < amounts.length; resource++) {
                if (amounts[resource] != null && amounts[resource].signum() > 0) {
                    knownMissing++;
                    relevantResources[resource] = true;
                    pending.addLast(resource);
                }
            }
            count = knownMissing;
            Adjacency users = new Adjacency(
                amounts.length,
                capture.candidates()
                    .size(),
                row -> capture.candidates()
                    .get(row)
                    .resourceId());
            while (!pending.isEmpty()) {
                int resource = pending.removeFirst();
                for (int candidate : users.rowsFor(resource)) {
                    int group = capture.candidates()
                        .get(candidate)
                        .ingredientId();
                    relevantGroups[group] = true;
                    int recipe = capture.ingredients()
                        .get(group)
                        .recipeId();
                    if (relevantRecipes[recipe]) continue;
                    relevantRecipes[recipe] = true;
                    for (int output : outputs(recipe)) {
                        int produced = capture.outputs()
                            .get(output)
                            .resourceId();
                        if (!relevantResources[produced]) {
                            relevantResources[produced] = true;
                            pending.addLast(produced);
                        }
                    }
                }
            }
            producers = new Adjacency(
                amounts.length,
                capture.outputs()
                    .size(),
                row -> capture.outputs()
                    .get(row)
                    .resourceId(),
                row -> relevantRecipes[capture.outputs()
                    .get(row)
                    .recipeId()]);
            ingredients = new Adjacency(
                capture.recipes()
                    .size(),
                capture.ingredients()
                    .size(),
                row -> capture.ingredients()
                    .get(row)
                    .recipeId(),
                row -> relevantGroups[row]);
            alternatives = new Adjacency(
                capture.ingredients()
                    .size(),
                capture.candidates()
                    .size(),
                row -> capture.candidates()
                    .get(row)
                    .ingredientId(),
                row -> relevantResources[capture.candidates()
                    .get(row)
                    .resourceId()]);
            int[] unlinkedRows = new int[count];
            int unlinkedCount = 0;
            for (int resource = 0; resource < amounts.length; resource++) {
                if (amounts[resource] != null && amounts[resource].signum() > 0 && !reachableResources[resource])
                    unlinkedRows[unlinkedCount++] = resource;
            }
            unlinked = new RowIds(unlinkedRows, 0, unlinkedCount);
            int[] unplacedRows = new int[unplacedRecipes.size()];
            int unplacedCount = 0;
            for (int recipe : unplacedRecipes) if (relevantRecipes[recipe]) unplacedRows[unplacedCount++] = recipe;
            unplaced = new RowIds(unplacedRows, 0, unplacedCount);
        }
    }

    private List<Integer> findUnplacedRecipes(boolean[] seenResources) {
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
            this(sources, count, sourceOf, row -> true);
        }

        private Adjacency(int sources, int count, IntUnaryOperator sourceOf, IntPredicate include) {
            offsets = new int[sources + 1];
            for (int row = 0; row < count; row++) if (include.test(row)) offsets[sourceOf.applyAsInt(row) + 1]++;
            for (int source = 1; source < offsets.length; source++) offsets[source] += offsets[source - 1];
            rows = new int[offsets[sources]];
            int[] next = offsets.clone();
            for (int row = 0; row < count; row++) if (include.test(row)) rows[next[sourceOf.applyAsInt(row)]++] = row;
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
