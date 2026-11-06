package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/**
 * Writes native pattern declarations and plan summaries into one capture builder, reusing the local ID of a
 * resource that was already described. Every write stops at the first rejection, like the builder itself.
 */
final class RecipeCaptureWriter {

    final CraftingRecipeCapture.Builder capture;
    private final Map<IAEStack<?>, Integer> resources = new HashMap<>();

    RecipeCaptureWriter(int maxEntries, int maxDescriptionCharacters) {
        capture = new CraftingRecipeCapture.Builder(maxEntries, maxDescriptionCharacters);
    }

    boolean available() {
        if (capture.hasCapacity()) return true;
        capture.stopAtEntryLimit();
        return false;
    }

    int resource(@NotNull IAEStack<?> stack) {
        Integer existing = resources.get(stack);
        if (existing != null) return existing;
        int id = capture.addResource(() -> AE2Controller.itemIdentities.describe((IAEKey) stack));
        if (id >= 0) resources.put(stack, id);
        return id;
    }

    /** Declares the plan's calculated output as the capture root. */
    boolean result(@NotNull IAEStack<?> output, boolean simulation) {
        if (!available()) return false;
        int resourceId = resource(output);
        if (resourceId < 0) return false;
        capture.result(resourceId, output.getStackSize(), simulation);
        return true;
    }

    boolean summary(@NotNull SummaryKind kind, @NotNull IAEStack<?> stack, long amount) {
        if (!available()) return false;
        int resourceId = resource(stack);
        return resourceId >= 0 && capture.addSummary(kind, resourceId, amount);
    }

    /** Declares the pattern's condensed inputs and outputs for an already added recipe, then completes it. */
    boolean recipe(@NotNull ICraftingPatternDetails pattern, int recipeId) {
        if (!available()) return false;
        for (IAEStack<?> input : pattern.getCondensedAEInputs()) {
            if (!available()) return false;
            int ingredientId = capture.addIngredient(recipeId, 1L);
            if (ingredientId < 0 || !available()) return false;
            int resourceId = resource(input);
            if (resourceId < 0 || !capture.addCandidate(ingredientId, resourceId, input.getStackSize())) return false;
        }
        if (!available()) return false;
        for (IAEStack<?> output : pattern.getCondensedAEOutputs()) {
            if (!available()) return false;
            int resourceId = resource(output);
            // The aggregate retains no designation of the output that selected this pattern.
            if (resourceId < 0 || !capture.addOutput(recipeId, resourceId, output.getStackSize(), null)) return false;
        }
        capture.finishRecipe(recipeId);
        return true;
    }
}
