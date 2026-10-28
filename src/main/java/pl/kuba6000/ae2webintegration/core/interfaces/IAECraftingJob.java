package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;

public interface IAECraftingJob extends IIdentityHolder {

    boolean web$isSimulation();

    long web$getByteTotal();

    ICraftingPlanSummary web$generateSummary(IAEGrid grid);

    /**
     * Capture selected-recipe declarations from a completed calculation on the server thread.
     * The caller must keep the completed job available and prevent concurrent submission mutation.
     * Returned data must be detached and retain no native objects or callbacks. Limits bound retained
     * rows and description characters, not native getter execution time. A null result means that
     * this job does not support aggregate capture; it is not an empty dependency graph.
     */
    default @Nullable CraftingRecipeCapture web$captureRecipes(int maxEntries, int maxDescriptionCharacters) {
        return null;
    }

}
