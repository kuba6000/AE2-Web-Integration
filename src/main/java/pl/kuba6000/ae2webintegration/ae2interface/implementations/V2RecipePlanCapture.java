package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEStack;
import appeng.crafting.v2.CraftingJobV2;
import appeng.crafting.v2.CraftingRequest;
import appeng.crafting.v2.CraftingRequest.UsedResolverEntry;
import appeng.crafting.v2.resolvers.CraftableItemResolver.CraftFromPatternTask;
import appeng.crafting.v2.resolvers.CraftableItemResolver.RequestAndPerCraftAmount;
import appeng.crafting.v2.resolvers.CraftingTask;
import appeng.crafting.v2.resolvers.EmitableItemResolver.EmitItemTask;
import appeng.crafting.v2.resolvers.ExtractItemResolver.ExtractItemTask;
import appeng.crafting.v2.resolvers.IgnoreMissingItemResolver.IgnoreMissingItemTask;
import appeng.crafting.v2.resolvers.SimulateMissingItemResolver.ConjureItemTask;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftFromPatternTaskData;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;

/**
 * Captures the selected recipes of a normal-mode calculation, aggregated per pattern exactly as the CPU
 * receives them (addCrafting sums by pattern). Only the tasks reachable through non-zero resolver
 * contributions are read; no request trace, consumer allocation or chosen substitute is inferred.
 */
public final class V2RecipePlanCapture {

    /** Scanning only reads native fields, so it may visit far more objects than the capture retains rows. */
    private static final int SCAN_BUDGET_PER_ENTRY = 50;

    private final RecipeCaptureWriter writer;
    private final Map<ICraftingPatternDetails, Long> crafts = new LinkedHashMap<>();
    private final Map<IAEStack<?>, Long> stored = new LinkedHashMap<>();
    private final Map<IAEStack<?>, Long> emitted = new LinkedHashMap<>();
    private final Map<IAEStack<?>, Long> missing = new LinkedHashMap<>();
    private final Set<CraftingTask> tasks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<CraftingRequest> pending = new ArrayDeque<>();
    private final long maxScanned;
    private long scanned;

    private V2RecipePlanCapture(int maxEntries, int maxDescriptionCharacters) {
        writer = new RecipeCaptureWriter(maxEntries, maxDescriptionCharacters);
        maxScanned = (long) maxEntries * SCAN_BUDGET_PER_ENTRY;
    }

    /**
     * Caller owns the completed job and invokes this on the server thread. Null means the plan cannot be
     * described faithfully: it contains a task type of another mod, or it needs more native objects than
     * the scan budget to read. The retained-row limits cannot express either reason.
     */
    public static @Nullable CraftingRecipeCapture capture(@NotNull CraftingJobV2<?> job, int maxEntries,
        int maxDescriptionCharacters) {
        if (!job.getErrorMessage()
            .isEmpty()) throw new IllegalStateException("Native crafting calculation failed");
        V2RecipePlanCapture reader = new V2RecipePlanCapture(maxEntries, maxDescriptionCharacters);
        if (!reader.scan(job.originalRequest)) return null;
        reader.write(job);
        return reader.writer.capture.build();
    }

    private boolean scan(@NotNull CraftingRequest root) {
        if (!enqueue(root)) return false;
        while (!pending.isEmpty()) {
            for (UsedResolverEntry entry : pending.removeFirst().usedResolvers) {
                if (!visit()) return false;
                // A refunded contribution stays in the list with size zero.
                if (entry.resolvedStack.getStackSize() > 0 && !resolve(entry)) return false;
            }
        }
        return true;
    }

    private boolean resolve(@NotNull UsedResolverEntry entry) {
        CraftingTask task = entry.task;
        Class<?> type = task.getClass();
        // Exact classes only: a subclass may change what its counters mean.
        if (type == CraftFromPatternTask.class) return crafting((CraftFromPatternTask) task);
        if (type == ExtractItemTask.class) return extraction((ExtractItemTask<?>) task);
        // These tasks fulfil their request once, so the entry's remaining amount is the task's amount.
        if (type == EmitItemTask.class) return add(emitted, entry.resolvedStack);
        if (type == IgnoreMissingItemTask.class || type == ConjureItemTask.class)
            return add(missing, entry.resolvedStack);
        return false;
    }

    private boolean crafting(@NotNull CraftFromPatternTask task) {
        if (!tasks.add(task)) return true;
        long totalCrafts = task.getTotalCraftsDone();
        // Like populatePlan, a zero-craft task contributes no recipe; its children are still traversed.
        if (totalCrafts > 0) crafts.merge(task.pattern, totalCrafts, Math::addExact);
        ICraftFromPatternTaskData children = (ICraftFromPatternTaskData) task;
        for (RequestAndPerCraftAmount child : children.web$getChildRequests()) {
            if (!enqueue(child.request)) return false;
        }
        for (CraftingRequest child : children.web$getChildRecursionRequests()
            .values()) {
            if (!enqueue(child)) return false;
        }
        return true;
    }

    private boolean extraction(@NotNull ExtractItemTask<?> task) {
        if (!tasks.add(task)) return true;
        // Byproduct reuse is not storage; it is deliberately left out of the initial amounts.
        for (IAEStack<?> removed : task.removedFromSystem) {
            if (!add(stored, removed)) return false;
        }
        return true;
    }

    private boolean add(@NotNull Map<IAEStack<?>, Long> totals, @NotNull IAEStack<?> stack) {
        if (!visit()) return false;
        long amount = stack.getStackSize();
        if (amount > 0) totals.merge(stack, amount, Math::addExact);
        return true;
    }

    private boolean enqueue(@NotNull CraftingRequest request) {
        if (!visit()) return false;
        pending.addLast(request);
        return true;
    }

    private boolean visit() {
        return ++scanned <= maxScanned;
    }

    private void write(@NotNull CraftingJobV2<?> job) {
        if (!writer.result(job.getOutput(), job.isSimulation())) return;
        if (!summaries(stored, SummaryKind.INITIAL) || !summaries(emitted, SummaryKind.EMITTED)
            || !summaries(missing, SummaryKind.MISSING)) return;
        for (Map.Entry<ICraftingPatternDetails, Long> task : crafts.entrySet()) {
            if (!writer.available()) return;
            int recipeId = writer.capture.addRecipe(task.getValue());
            if (recipeId < 0 || !writer.recipe(task.getKey(), recipeId)) return;
        }
    }

    private boolean summaries(@NotNull Map<IAEStack<?>, Long> amounts, @NotNull SummaryKind kind) {
        for (Map.Entry<IAEStack<?>, Long> entry : amounts.entrySet()) {
            if (!writer.summary(kind, entry.getKey(), entry.getValue())) return false;
        }
        return true;
    }
}
