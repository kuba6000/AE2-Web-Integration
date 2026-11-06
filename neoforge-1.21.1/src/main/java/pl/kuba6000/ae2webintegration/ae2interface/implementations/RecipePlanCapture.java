package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Server-thread capture of selected recipe declarations from a completed native plan. */
public final class RecipePlanCapture {

    private final CraftingRecipeCapture.Builder capture;
    private final Map<AEKey, Integer> resources = new HashMap<>();

    private RecipePlanCapture(int maxEntries, int maxDescriptionCharacters) {
        capture = new CraftingRecipeCapture.Builder(maxEntries, maxDescriptionCharacters);
    }

    public static @NotNull CraftingRecipeCapture capture(@NotNull ICraftingPlan plan, int maxEntries,
        int maxDescriptionCharacters) {
        RecipePlanCapture reader = new RecipePlanCapture(maxEntries, maxDescriptionCharacters);
        reader.read(plan);
        return reader.capture.build();
    }

    private void read(@NotNull ICraftingPlan plan) {
        if (!available()) return;
        GenericStack result = plan.finalOutput();
        int resultId = resource(result.what());
        if (resultId < 0) return;
        capture.result(resultId, result.amount(), plan.simulation());
        if (!summaries(plan.usedItems(), SummaryKind.INITIAL) || !summaries(plan.emittedItems(), SummaryKind.EMITTED)
            || !summaries(plan.missingItems(), SummaryKind.MISSING)) return;
        Iterator<Map.Entry<IPatternDetails, Long>> recipes = plan.patternTimes()
            .entrySet()
            .iterator();
        while (recipes.hasNext()) {
            if (!available()) return;
            Map.Entry<IPatternDetails, Long> entry = recipes.next();
            int recipeId = capture.addRecipe(entry.getValue());
            if (recipeId < 0 || !recipe(entry.getKey(), recipeId)) return;
        }
    }

    private boolean recipe(@NotNull IPatternDetails pattern, int recipeId) {
        // A budget cannot preempt an addon's getter. Do not invoke one after exhausting it.
        if (!available()) return false;
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        for (IPatternDetails.IInput input : inputs) {
            if (!available()) return false;
            int ingredientId = capture.addIngredient(recipeId, input.getMultiplier());
            if (ingredientId < 0 || !available()) return false;
            GenericStack[] candidates = input.getPossibleInputs();
            for (GenericStack candidate : candidates) {
                if (!available()) return false;
                int resourceId = resource(candidate.what());
                if (resourceId < 0 || !capture.addCandidate(ingredientId, resourceId, candidate.amount())) return false;
            }
        }
        if (!available()) return false;
        GenericStack primary = pattern.getPrimaryOutput();
        for (GenericStack output : pattern.getOutputs()) {
            if (!available()) return false;
            int resourceId = resource(output.what());
            if (resourceId < 0 || !capture.addOutput(recipeId, resourceId, output.amount(), output.equals(primary)))
                return false;
        }
        capture.finishRecipe(recipeId);
        return true;
    }

    private boolean summaries(@NotNull KeyCounter counter, @NotNull SummaryKind kind) {
        Iterator<Object2LongMap.Entry<AEKey>> entries = counter.iterator();
        while (entries.hasNext()) {
            if (!available()) return false;
            Object2LongMap.Entry<AEKey> entry = entries.next();
            int resourceId = resource(entry.getKey());
            if (resourceId < 0 || !capture.addSummary(kind, resourceId, entry.getLongValue())) return false;
        }
        return true;
    }

    private int resource(@NotNull AEKey key) {
        Integer existing = resources.get(key);
        if (existing != null) return existing;
        int id = capture.addResource(() -> AE2Controller.itemIdentities.describe((IAEKey) (Object) key));
        if (id >= 0) resources.put(key, id);
        return id;
    }

    private boolean available() {
        if (capture.hasCapacity()) return true;
        capture.stopAtEntryLimit();
        return false;
    }
}
