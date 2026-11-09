package pl.kuba6000.ae2webintegration.core.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Detached selected-recipe declarations, not a trace of actual input selection or material allocation.
 * Resource, recipe and ingredient IDs index their respective tables. Candidate/output amounts are
 * native units per template/craft; root and summary amounts are plan totals. Craft counts and
 * ingredient multipliers are deliberately not multiplied.
 * A null result means the root has not been captured; such a capture is not a ready web plan.
 */
@Desugar
public record CraftingRecipeCapture(@Nullable Amount result, boolean simulated,
    @NotNull List<ResourceDescriptor> resources, @NotNull List<Recipe> recipes, @NotNull List<Ingredient> ingredients,
    @NotNull List<Candidate> candidates, @NotNull List<Output> outputs, @NotNull List<Summary> summaries,
    @NotNull Set<Limit> limits) {

    /** Copies detached tables. Callers constructing directly must supply consistent local references. */
    public CraftingRecipeCapture {
        resources = copy(resources);
        recipes = copy(recipes);
        ingredients = copy(ingredients);
        candidates = copy(candidates);
        outputs = copy(outputs);
        summaries = copy(summaries);
        EnumSet<Limit> limitCopy = EnumSet.noneOf(Limit.class);
        limitCopy.addAll(limits);
        limits = Collections.unmodifiableSet(limitCopy);
    }

    private static <T> List<T> copy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public enum SummaryKind {
        INITIAL,
        EMITTED,
        MISSING
    }

    public enum Limit {
        ENTRIES,
        DESCRIPTION_CHARACTERS
    }

    @Desugar
    public record Amount(int resourceId, long amount) {}

    @Desugar
    public record Recipe(long crafts, boolean complete) {}

    /** Alternatives belong to one requirement; multiplier counts templates, not raw resource units. */
    @Desugar
    public record Ingredient(int recipeId, long multiplier) {}

    @Desugar
    public record Candidate(int ingredientId, int resourceId, long amount) {}

    /** Primary designation is true/false when known, or null when the native plan does not retain it. */
    @Desugar
    public record Output(int recipeId, int resourceId, long amount, @Nullable Boolean primary) {}

    @Desugar
    public record Summary(@NotNull SummaryKind kind, int resourceId, long amount) {}

    /**
     * Single-threaded capture sink. Native resource interning belongs to the adapter. Every appended
     * row consumes one entry; descriptions additionally consume UTF-16 characters. These are retained
     * data bounds, not bounds on native getter cost or JVM heap bytes. Stop traversal on first rejection.
     * Resources with equal descriptions remain distinct unless the adapter reuses their local ID.
     */
    public static final class Builder {

        private final CaptureBudget budget;
        private final List<Recipe> recipes = new ArrayList<>();
        private final List<Ingredient> ingredients = new ArrayList<>();
        private final List<Candidate> candidates = new ArrayList<>();
        private final List<Output> outputs = new ArrayList<>();
        private final List<Summary> summaries = new ArrayList<>();
        private @Nullable Amount result;
        private boolean simulated;

        public Builder(int maxEntries, int maxDescriptionCharacters) {
            budget = new CaptureBudget(maxEntries, maxDescriptionCharacters);
        }

        /** Check before native getters; this does not itself declare omitted data. */
        public boolean hasCapacity() {
            return budget.hasCapacity();
        }

        /** Report remaining native data without reading it after hasCapacity returned false. */
        public void stopAtEntryLimit() {
            budget.stopAtEntryLimit();
        }

        /** Invokes the native description supplier only after entry admission; rejection returns -1. */
        public int addResource(@NotNull Supplier<ResourceDescriptor> descriptor) {
            return budget.addResource(descriptor);
        }

        public void result(int resourceId, long amount, boolean simulation) {
            budget.resources.get(resourceId);
            result = new Amount(resourceId, amount);
            simulated = simulation;
        }

        public int addRecipe(long crafts) {
            if (!budget.admit()) return -1;
            recipes.add(new Recipe(crafts, false));
            return recipes.size() - 1;
        }

        public int addIngredient(int recipeId, long multiplier) {
            requireOpen(recipeId);
            if (!budget.admit()) return -1;
            ingredients.add(new Ingredient(recipeId, multiplier));
            return ingredients.size() - 1;
        }

        public boolean addCandidate(int ingredientId, int resourceId, long amount) {
            requireOpen(
                ingredients.get(ingredientId)
                    .recipeId());
            budget.resources.get(resourceId);
            if (!budget.admit()) return false;
            candidates.add(new Candidate(ingredientId, resourceId, amount));
            return true;
        }

        public boolean addOutput(int recipeId, int resourceId, long amount, @Nullable Boolean primary) {
            requireOpen(recipeId);
            budget.resources.get(resourceId);
            if (!budget.admit()) return false;
            outputs.add(new Output(recipeId, resourceId, amount, primary));
            return true;
        }

        public boolean addSummary(@NotNull SummaryKind kind, int resourceId, long amount) {
            budget.resources.get(resourceId);
            if (!budget.admit()) return false;
            summaries.add(new Summary(kind, resourceId, amount));
            return true;
        }

        /** Call only after every native ingredient/candidate/output was admitted. */
        public void finishRecipe(int recipeId) {
            if (!budget.limits.isEmpty()) throw new IllegalStateException("Capture was truncated");
            requireOpen(recipeId);
            recipes.set(
                recipeId,
                new Recipe(
                    recipes.get(recipeId)
                        .crafts(),
                    true));
        }

        private void requireOpen(int recipeId) {
            if (recipes.get(recipeId)
                .complete()) throw new IllegalStateException("Recipe capture already complete");
        }

        public @NotNull CraftingRecipeCapture build() {
            return new CraftingRecipeCapture(
                result,
                simulated,
                budget.resources,
                recipes,
                ingredients,
                candidates,
                outputs,
                summaries,
                budget.limits);
        }

    }
}
