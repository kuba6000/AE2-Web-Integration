package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEStack;
import appeng.crafting.fast.CraftingJobFast;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingJobFastData;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Captures Lite's selected recipe declarations; no request trace or chosen substitutes are inferred. */
public final class LiteRecipePlanCapture {

    private final CraftingRecipeCapture.Builder capture;
    private final Map<IAEStack<?>, Integer> resources = new HashMap<>();

    private LiteRecipePlanCapture(int maxEntries, int maxDescriptionCharacters) {
        capture = new CraftingRecipeCapture.Builder(maxEntries, maxDescriptionCharacters);
    }

    /** Caller owns the completed job and invokes this on the server thread. */
    public static @NotNull CraftingRecipeCapture capture(@NotNull CraftingJobFast<?> job, int maxEntries,
        int maxDescriptionCharacters) {
        // Lite catches calculation errors and can return a completed future containing partial maps.
        if (!job.getErrorMessage()
            .isEmpty()) throw new IllegalStateException("Native Lite calculation failed");
        LiteRecipePlanCapture reader = new LiteRecipePlanCapture(maxEntries, maxDescriptionCharacters);
        reader.read(job);
        return reader.capture.build();
    }

    private void read(@NotNull CraftingJobFast<?> job) {
        if (!available()) return;
        IAEStack<?> output = job.getOutput();
        int resultId = resource(output);
        if (resultId < 0) return;
        capture.result(resultId, output.getStackSize(), job.isSimulation());
        ICraftingJobFastData data = (ICraftingJobFastData) (Object) job;
        if (!summaries(data.web$getIngredients(), SummaryKind.INITIAL)
            || !summaries(data.web$getMissingIngredients(), SummaryKind.MISSING)) return;
        Iterator<Object2LongMap.Entry<ICraftingPatternDetails>> tasks = data.web$getTasks()
            .object2LongEntrySet()
            .iterator();
        while (tasks.hasNext()) {
            if (!available()) return;
            Object2LongMap.Entry<ICraftingPatternDetails> task = tasks.next();
            int recipeId = capture.addRecipe(task.getLongValue());
            if (recipeId < 0 || !recipe(task.getKey(), recipeId)) return;
        }
    }

    private boolean recipe(@NotNull ICraftingPatternDetails pattern, int recipeId) {
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

    private boolean summaries(@NotNull AbstractObject2LongMap<IAEStack<?>> amounts, @NotNull SummaryKind kind) {
        Iterator<Object2LongMap.Entry<IAEStack<?>>> entries = amounts.object2LongEntrySet()
            .iterator();
        while (entries.hasNext()) {
            if (!available()) return false;
            Object2LongMap.Entry<IAEStack<?>> entry = entries.next();
            int resourceId = resource(entry.getKey());
            if (resourceId < 0 || !capture.addSummary(kind, resourceId, entry.getLongValue())) return false;
        }
        return true;
    }

    private int resource(@NotNull IAEStack<?> stack) {
        Integer existing = resources.get(stack);
        if (existing != null) return existing;
        int id = capture.addResource(() -> AE2Controller.itemIdentities.describe((IAEKey) stack));
        if (id >= 0) resources.put(stack, id);
        return id;
    }

    private boolean available() {
        if (capture.hasCapacity()) return true;
        capture.stopAtEntryLimit();
        return false;
    }
}
