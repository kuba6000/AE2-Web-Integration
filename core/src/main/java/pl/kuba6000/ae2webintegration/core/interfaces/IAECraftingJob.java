package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture;

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

    /**
     * Capture the per-occurrence trace the native job recorded for a completed calculation, on the server
     * thread, under the same ownership rules as {@link #web$captureRecipes}. Returned data must be detached.
     * Only native jobs that record which request was satisfied by which storage, emission, missing amount
     * or craft implement it; the aggregate capture remains the fallback. A null result, or a trace that never
     * reached its root request, means that this job has no trace; it is not an empty dependency graph.
     */
    default @Nullable CraftingTraceCapture web$captureTrace(int maxEntries, int maxDescriptionCharacters) {
        return null;
    }

}
