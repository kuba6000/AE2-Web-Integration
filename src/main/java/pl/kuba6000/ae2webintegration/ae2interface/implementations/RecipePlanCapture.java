package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.CraftingJob;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingTreeNodeData;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingTreeProcessData;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/**
 * Captures what AE2 itself reads from a completed UEL calculation tree. Node.setJob (CPU submission)
 * and populatePlan both walk every candidate process below the root and read only the counters stored
 * in the tree: a process contributes its crafts to its pattern, a node contributes its used,
 * emitted and missing amounts. Recipes are therefore the summed process crafts per pattern, exactly as
 * the CPU accumulates them. Counters left by failed alternatives are included, as AE2 includes them.
 * Declared inputs are the pattern's condensed inputs; substitute alternatives are not enumerated.
 */
public final class RecipePlanCapture {

    /** The UEL tree is unshared and holds every candidate process, so visits far outnumber retained rows. */
    private static final int SCAN_VISITS_PER_ENTRY = 50;

    private final CraftingRecipeCapture.Builder capture;
    private final long maxVisits;
    private final Map<IAEItemStack, Integer> resources = new HashMap<>();
    private final Map<ICraftingPatternDetails, Long> crafts = new LinkedHashMap<>();
    private final Map<IAEItemStack, Long> initial = new LinkedHashMap<>();
    private final Map<IAEItemStack, Long> emitted = new LinkedHashMap<>();
    private final Map<IAEItemStack, Long> missing = new LinkedHashMap<>();
    private long visited;

    private RecipePlanCapture(int maxEntries, int maxDescriptionCharacters) {
        capture = new CraftingRecipeCapture.Builder(maxEntries, maxDescriptionCharacters);
        maxVisits = (long) maxEntries * SCAN_VISITS_PER_ENTRY;
    }

    /**
     * Caller owns the completed job and invokes this on the server thread. Returns null when the tree
     * holds more than 50 times maxEntries nodes, processes and used entries: the scan reads plain fields
     * and is bounded separately from retained rows. The builder can only report truncation of rows it
     * has admitted, and the aggregates cannot be completed without the full scan.
     */
    public static @Nullable CraftingRecipeCapture capture(@NotNull CraftingJob job, int maxEntries,
        int maxDescriptionCharacters) {
        RecipePlanCapture reader = new RecipePlanCapture(maxEntries, maxDescriptionCharacters);
        if (!reader.scan(job.getTree())) return null;
        reader.read(job);
        return reader.capture.build();
    }

    private boolean scan(@NotNull CraftingTreeNode root) {
        Queue<CraftingTreeNode> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            if (visitExceedsBudget()) return false;
            ICraftingTreeNodeData node = (ICraftingTreeNodeData) (Object) pending.remove();
            IAEItemStack what = node.web$getWhat();
            add(emitted, what, node.web$getHowManyEmitted());
            add(missing, what, node.web$getMissing());
            for (IAEItemStack used : node.web$getUsed()) {
                if (visitExceedsBudget()) return false;
                add(initial, used, used.getStackSize());
            }
            for (CraftingTreeProcess process : node.web$getProcesses()) {
                if (visitExceedsBudget()) return false;
                ICraftingTreeProcessData data = (ICraftingTreeProcessData) (Object) process;
                // Zero crafts are not submitted work, yet the children are still read, as setJob does.
                if (data.web$getCrafts() > 0) crafts.merge(data.web$getDetails(), data.web$getCrafts(), Math::addExact);
                pending.addAll(
                    data.web$getNodes()
                        .keySet());
            }
        }
        return true;
    }

    private boolean visitExceedsBudget() {
        return ++visited > maxVisits;
    }

    private static void add(@NotNull Map<IAEItemStack, Long> amounts, @NotNull IAEItemStack what, long amount) {
        if (amount > 0) amounts.merge(what, amount, Math::addExact);
    }

    private void read(@NotNull CraftingJob job) {
        if (!available()) return;
        IAEItemStack output = job.getOutput();
        int resultId = resource(output);
        if (resultId < 0) return;
        capture.result(resultId, output.getStackSize(), job.isSimulation());
        if (!summaries(initial, SummaryKind.INITIAL) || !summaries(emitted, SummaryKind.EMITTED)
            || !summaries(missing, SummaryKind.MISSING)) return;
        for (Map.Entry<ICraftingPatternDetails, Long> task : crafts.entrySet()) {
            if (!available()) return;
            int recipeId = capture.addRecipe(task.getValue());
            if (recipeId < 0 || !recipe(task.getKey(), recipeId)) return;
        }
    }

    private boolean recipe(@NotNull ICraftingPatternDetails pattern, int recipeId) {
        // A budget cannot preempt an addon's getter. Do not invoke one after exhausting it.
        if (!available()) return false;
        for (IAEItemStack input : pattern.getCondensedInputs()) {
            if (!available()) return false;
            int ingredientId = capture.addIngredient(recipeId, 1L);
            if (ingredientId < 0 || !available()) return false;
            int resourceId = resource(input);
            if (resourceId < 0 || !capture.addCandidate(ingredientId, resourceId, input.getStackSize())) return false;
        }
        if (!available()) return false;
        for (IAEItemStack output : pattern.getCondensedOutputs()) {
            if (!available()) return false;
            int resourceId = resource(output);
            // The aggregate retains no designation of the output that selected this pattern.
            if (resourceId < 0 || !capture.addOutput(recipeId, resourceId, output.getStackSize(), null)) return false;
        }
        capture.finishRecipe(recipeId);
        return true;
    }

    private boolean summaries(@NotNull Map<IAEItemStack, Long> amounts, @NotNull SummaryKind kind) {
        for (Map.Entry<IAEItemStack, Long> entry : amounts.entrySet()) {
            if (!available()) return false;
            int resourceId = resource(entry.getKey());
            if (resourceId < 0 || !capture.addSummary(kind, resourceId, entry.getValue())) return false;
        }
        return true;
    }

    private int resource(@NotNull IAEItemStack stack) {
        Integer existing = resources.get(stack);
        if (existing != null) return existing;
        int id = capture.addResource(() -> AE2Controller.itemIdentities.describe((IAEKey) (Object) stack));
        if (id >= 0) resources.put(stack, id);
        return id;
    }

    private boolean available() {
        if (capture.hasCapacity()) return true;
        capture.stopAtEntryLimit();
        return false;
    }
}
