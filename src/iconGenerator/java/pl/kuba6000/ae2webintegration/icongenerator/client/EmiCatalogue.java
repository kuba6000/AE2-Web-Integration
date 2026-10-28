package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiReloadManager;

/** Optional bridge; loaded only after EMI presence is checked. Readiness is pinned to EMI 1.1.21. */
final class EmiCatalogue {

    private final List<EmiStack> stacks;

    private final EmiRecipeManager manager;
    private final List<EmiRecipe> recipes;
    private final List<EmiRecipeCategory> categories;
    private int recipeIndex;
    private int categoryIndex;
    private @Nullable EmiRecipe recipe;
    private int partIndex;
    private List<? extends EmiIngredient> ingredients = List.of();
    private int ingredientIndex;
    private int ingredientCount;
    private List<EmiStack> alternatives = List.of();
    private int alternativeIndex;
    private int alternativeCount;
    private @Nullable EmiStack remainderSource;
    private boolean recipesDone;
    private long recipeCandidates;
    private String recipeContext = "emi-recipes";
    private String partContext = "emi-recipes";

    private enum RecipePart {
        INPUTS,
        CATALYSTS,
        OUTPUTS
    }

    private static final RecipePart[] RECIPE_PARTS = RecipePart.values();

    private EmiCatalogue(@NotNull List<EmiStack> stacks, @NotNull EmiRecipeManager manager) {
        this.stacks = stacks;
        this.manager = manager;
        recipes = manager.getRecipes();
        categories = manager.getCategories();
    }

    static @Nullable EmiCatalogue ready() {
        if (!EmiReloadManager.isLoaded()) return null;
        List<EmiStack> stacks = EmiApi.getIndexStacks();
        EmiRecipeManager manager = EmiApi.getRecipeManager();
        if (!EmiReloadManager.isLoaded() || stacks != EmiApi.getIndexStacks()) return null;
        // Completed EMI reload publishes an immutable list. Retain that generation, never the live mutable builder.
        // EMI may replace the manager while indexing these same recipes; retain this finalized snapshot.
        return new EmiCatalogue(stacks, manager);
    }

    static boolean failed() {
        return EmiReloadManager.getStatus() == -1;
    }

    void verify() {
        if (!EmiReloadManager.isLoaded() || stacks != EmiApi.getIndexStacks()) throw new Changed();
    }

    int size() {
        return stacks.size();
    }

    @Nullable
    IconCandidate candidate(int index, @NotNull HolderLookup.Provider registries) {
        EmiStack stack = stacks.get(index);
        if (stack.isEmpty()) return null;
        return candidate(stack, "emi/" + index, registries);
    }

    boolean recipesDone() {
        return recipesDone;
    }

    int recipesVisited() {
        return recipeIndex;
    }

    long recipeCandidates() {
        return recipeCandidates;
    }

    @NotNull
    String recipeContext() {
        return recipeContext;
    }

    /** One candidate or cursor transition per call; plugin failures cannot retry the same work indefinitely. */
    @Nullable
    IconCandidate nextRecipe(@NotNull HolderLookup.Provider registries) {
        if (remainderSource != null) {
            EmiStack source = remainderSource;
            remainderSource = null;
            recipeContext += "/remainder";
            IconCandidate candidate = candidate(source.getRemainder(), recipeContext, registries);
            if (candidate != null) recipeCandidates++;
            return candidate;
        }
        if (alternativeIndex < alternativeCount) {
            int index = alternativeIndex++;
            recipeContext = partContext + "/ingredient/" + (ingredientIndex - 1) + "/alternative/" + index;
            EmiStack stack = alternatives.get(index);
            remainderSource = stack;
            IconCandidate candidate = candidate(stack, recipeContext, registries);
            if (candidate != null) recipeCandidates++;
            return candidate;
        }
        if (ingredientIndex < ingredientCount) {
            alternativeIndex = alternativeCount = 0;
            alternatives = List.of();
            int index = ingredientIndex++;
            recipeContext = partContext + "/ingredient/" + index;
            alternatives = ingredients.get(index)
                .getEmiStacks();
            alternativeCount = alternatives.size();
            return null;
        }
        ingredientIndex = ingredientCount = 0;
        ingredients = List.of();
        if (recipe != null && partIndex < RECIPE_PARTS.length) {
            RecipePart part = RECIPE_PARTS[partIndex++];
            partContext = "emi-recipe/" + (recipeIndex - 1) + "/" + part;
            recipeContext = partContext;
            ingredients = switch (part) {
                case INPUTS -> recipe.getInputs();
                case CATALYSTS -> recipe.getCatalysts();
                case OUTPUTS -> recipe.getOutputs();
            };
            ingredientCount = ingredients.size();
            return null;
        }
        if (recipeIndex < recipes.size()) {
            recipe = null;
            partIndex = 0;
            recipeContext = "emi-recipe/" + recipeIndex;
            recipe = recipes.get(recipeIndex++);
            return null;
        }
        if (categoryIndex < categories.size()) {
            partContext = "emi-workstation/" + categoryIndex;
            recipeContext = partContext;
            ingredients = manager.getWorkstations(categories.get(categoryIndex++));
            ingredientCount = ingredients.size();
            return null;
        }
        recipesDone = true;
        recipe = null;
        alternatives = List.of();
        return null;
    }

    private @Nullable IconCandidate candidate(@NotNull EmiStack stack, @NotNull String context,
        @NotNull HolderLookup.Provider registries) {
        Object key = stack.getKey();
        if (key instanceof Item item) {
            if (item == Items.AIR) return null;
            // Recipe catalysts can have amount zero; normalize a copy before native emptiness checks.
            EmiStack owned = stack.copy()
                .setAmount(1);
            return IconCandidate.item(context, owned.getItemStack(), registries);
        }
        if (key instanceof Fluid fluid) {
            if (fluid == Fluids.EMPTY) return null;
            FluidStack owned = new FluidStack(fluid, 1);
            owned.applyComponents(stack.getComponentChanges());
            return IconCandidate.fluid(context, owned, registries);
        }
        if (stack.isEmpty()) return null;
        throw new UnsupportedOperationException(
            "Unsupported EMI ingredient kind " + key.getClass()
                .getName());
    }

    static final class Changed extends IllegalStateException {

        Changed() {
            super("EMI catalogue changed during export; start a new export after reload completes");
        }
    }
}
