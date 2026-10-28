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

        private final List<ResourceDescriptor> resources = new ArrayList<>();
        private final List<Recipe> recipes = new ArrayList<>();
        private final List<Ingredient> ingredients = new ArrayList<>();
        private final List<Candidate> candidates = new ArrayList<>();
        private final List<Output> outputs = new ArrayList<>();
        private final List<Summary> summaries = new ArrayList<>();
        private final EnumSet<Limit> limits = EnumSet.noneOf(Limit.class);
        private @Nullable Amount result;
        private boolean simulated;
        private final int maxEntries;
        private int entries;
        private final int maxDescriptionCharacters;
        private long descriptionCharacters;

        public Builder(int maxEntries, int maxDescriptionCharacters) {
            if (maxEntries < 0 || maxDescriptionCharacters < 0)
                throw new IllegalArgumentException("Negative capture budget");
            this.maxEntries = maxEntries;
            this.maxDescriptionCharacters = maxDescriptionCharacters;
        }

        /** Check before native getters; this does not itself declare omitted data. */
        public boolean hasCapacity() {
            return limits.isEmpty() && entries < maxEntries;
        }

        /** Report remaining native data without reading it after hasCapacity returned false. */
        public void stopAtEntryLimit() {
            if (entries < maxEntries) throw new IllegalStateException("Entry budget not exhausted");
            limits.add(Limit.ENTRIES);
        }

        private boolean admit() {
            if (!limits.isEmpty()) return false;
            if (entries >= maxEntries) {
                limits.add(Limit.ENTRIES);
                return false;
            }
            entries++;
            return true;
        }

        /** Invokes the native description supplier only after entry admission; rejection returns -1. */
        public int addResource(@NotNull Supplier<ResourceDescriptor> descriptor) {
            if (!admit()) return -1;
            ResourceDescriptor value = descriptor.get();
            long characters = (long) value.displayName()
                .length() + length(value.registryNamespace()) + length(value.registryPath());
            if (characters > maxDescriptionCharacters - descriptionCharacters) {
                entries--;
                limits.add(Limit.DESCRIPTION_CHARACTERS);
                return -1;
            }
            descriptionCharacters += characters;
            resources.add(value);
            return resources.size() - 1;
        }

        private static int length(@Nullable String value) {
            return value == null ? 0 : value.length();
        }

        public void result(int resourceId, long amount, boolean simulation) {
            resources.get(resourceId);
            result = new Amount(resourceId, amount);
            simulated = simulation;
        }

        public int addRecipe(long crafts) {
            if (!admit()) return -1;
            recipes.add(new Recipe(crafts, false));
            return recipes.size() - 1;
        }

        public int addIngredient(int recipeId, long multiplier) {
            requireOpen(recipeId);
            if (!admit()) return -1;
            ingredients.add(new Ingredient(recipeId, multiplier));
            return ingredients.size() - 1;
        }

        public boolean addCandidate(int ingredientId, int resourceId, long amount) {
            requireOpen(
                ingredients.get(ingredientId)
                    .recipeId());
            resources.get(resourceId);
            if (!admit()) return false;
            candidates.add(new Candidate(ingredientId, resourceId, amount));
            return true;
        }

        public boolean addOutput(int recipeId, int resourceId, long amount, @Nullable Boolean primary) {
            requireOpen(recipeId);
            resources.get(resourceId);
            if (!admit()) return false;
            outputs.add(new Output(recipeId, resourceId, amount, primary));
            return true;
        }

        public boolean addSummary(@NotNull SummaryKind kind, int resourceId, long amount) {
            resources.get(resourceId);
            if (!admit()) return false;
            summaries.add(new Summary(kind, resourceId, amount));
            return true;
        }

        /** Call only after every native ingredient/candidate/output was admitted. */
        public void finishRecipe(int recipeId) {
            if (!limits.isEmpty()) throw new IllegalStateException("Capture was truncated");
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
                resources,
                recipes,
                ingredients,
                candidates,
                outputs,
                summaries,
                limits);
        }

    }
}
