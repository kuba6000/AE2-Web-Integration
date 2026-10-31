package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.Iterator;

import org.jetbrains.annotations.NotNull;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEStack;
import appeng.crafting.fast.CraftingJobFast;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingJobFastData;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;

/** Captures Lite's selected recipe declarations; no request trace or chosen substitutes are inferred. */
public final class LiteRecipePlanCapture {

    private final RecipeCaptureWriter writer;

    private LiteRecipePlanCapture(int maxEntries, int maxDescriptionCharacters) {
        writer = new RecipeCaptureWriter(maxEntries, maxDescriptionCharacters);
    }

    /** Caller owns the completed job and invokes this on the server thread. */
    public static @NotNull CraftingRecipeCapture capture(@NotNull CraftingJobFast<?> job, int maxEntries,
        int maxDescriptionCharacters) {
        // Lite catches calculation errors and can return a completed future containing partial maps.
        if (!job.getErrorMessage()
            .isEmpty()) throw new IllegalStateException("Native Lite calculation failed");
        LiteRecipePlanCapture reader = new LiteRecipePlanCapture(maxEntries, maxDescriptionCharacters);
        reader.read(job);
        return reader.writer.capture.build();
    }

    private void read(@NotNull CraftingJobFast<?> job) {
        if (!writer.result(job.getOutput(), job.isSimulation())) return;
        ICraftingJobFastData data = (ICraftingJobFastData) (Object) job;
        if (!summaries(data.web$getIngredients(), SummaryKind.INITIAL)
            || !summaries(data.web$getMissingIngredients(), SummaryKind.MISSING)) return;
        Iterator<Object2LongMap.Entry<ICraftingPatternDetails>> tasks = data.web$getTasks()
            .object2LongEntrySet()
            .iterator();
        while (tasks.hasNext()) {
            if (!writer.available()) return;
            Object2LongMap.Entry<ICraftingPatternDetails> task = tasks.next();
            int recipeId = writer.capture.addRecipe(task.getLongValue());
            if (recipeId < 0 || !writer.recipe(task.getKey(), recipeId)) return;
        }
    }

    private boolean summaries(@NotNull AbstractObject2LongMap<IAEStack<?>> amounts, @NotNull SummaryKind kind) {
        Iterator<Object2LongMap.Entry<IAEStack<?>>> entries = amounts.object2LongEntrySet()
            .iterator();
        while (entries.hasNext()) {
            Object2LongMap.Entry<IAEStack<?>> entry = entries.next();
            if (!writer.summary(kind, entry.getKey(), entry.getLongValue())) return false;
        }
        return true;
    }
}
