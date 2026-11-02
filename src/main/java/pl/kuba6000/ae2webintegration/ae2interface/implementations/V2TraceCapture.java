package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.ContributionKind;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/**
 * Captures a normal-mode calculation as the job recorded it: every native request is a request, its non-zero
 * resolver entries are its contributions in native order, and each pattern task keeps its own craft count,
 * declared recipe and the child requests it issued. Requests and tasks are read once by identity, so a
 * task reached from several entries is one task. Amounts are the entries' own; nothing is apportioned.
 */
public final class V2TraceCapture {

    /** Scanning only reads native fields, so it may visit far more objects than the capture retains rows. */
    private static final int SCAN_BUDGET_PER_ENTRY = 50;

    private final CraftingTraceCapture.Builder capture;
    private final Map<IAEStack<?>, Integer> resources = new HashMap<>();
    private final Map<CraftingRequest, Integer> requestIds = new IdentityHashMap<>();
    private final Map<CraftFromPatternTask, Integer> taskIds = new IdentityHashMap<>();
    private final Set<ExtractItemTask<?>> extractions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<CraftingRequest> pendingRequests = new ArrayDeque<>();
    private final Deque<CraftFromPatternTask> pendingTasks = new ArrayDeque<>();
    private final long maxScanned;
    private long scanned;
    private boolean scanExhausted;

    private V2TraceCapture(int maxEntries, int maxDescriptionCharacters) {
        capture = new CraftingTraceCapture.Builder(maxEntries, maxDescriptionCharacters);
        maxScanned = (long) maxEntries * SCAN_BUDGET_PER_ENTRY;
    }

    /**
     * Caller owns the completed job and invokes this on the server thread. Null means the plan needs more
     * native objects than the scan budget to read; the retained-row limits cannot express that. Reaching a
     * retained-row limit instead returns the capture read so far with its limit reason.
     */
    public static @Nullable CraftingTraceCapture capture(@NotNull CraftingJobV2<?> job, int maxEntries,
        int maxDescriptionCharacters) {
        if (!job.getErrorMessage()
            .isEmpty()) throw new IllegalStateException("Native crafting calculation failed");
        V2TraceCapture reader = new V2TraceCapture(maxEntries, maxDescriptionCharacters);
        reader.read(job);
        return reader.scanExhausted ? null : reader.capture.build();
    }

    private void read(@NotNull CraftingJobV2<?> job) {
        int root = request(job.originalRequest);
        if (root < 0) return;
        capture.root(root, job.isSimulation());
        // Every step stops at the first rejection, either of the scan budget or of the builder.
        while (!pendingRequests.isEmpty() || !pendingTasks.isEmpty()) {
            boolean read = pendingRequests.isEmpty() ? readTask(pendingTasks.removeFirst())
                : readRequest(pendingRequests.removeFirst());
            if (!read) return;
        }
    }

    /** The request's id, adding it to the capture and queueing its contributions the first time it is met. */
    private int request(@NotNull CraftingRequest request) {
        Integer existing = requestIds.get(request);
        if (existing != null) return existing;
        if (!visit()) return -1;
        int resourceId = resource(request.stack);
        if (resourceId < 0 || !available()) return -1;
        int id = capture.addRequest(resourceId, request.stack.getStackSize());
        if (id < 0) return -1;
        requestIds.put(request, id);
        pendingRequests.addLast(request);
        return id;
    }

    private boolean readRequest(@NotNull CraftingRequest request) {
        int requestId = requestIds.get(request);
        for (UsedResolverEntry entry : request.usedResolvers) {
            if (!visit()) return false;
            long amount = entry.resolvedStack.getStackSize();
            // A refunded contribution stays in the list with size zero.
            if (amount > 0 && !contribution(requestId, entry.task, amount)) return false;
        }
        return true;
    }

    private boolean contribution(int requestId, @NotNull CraftingTask task, long amount) {
        Class<?> type = task.getClass();
        // Exact classes only: a subclass may change what its counters mean.
        if (type == CraftFromPatternTask.class) return crafting(requestId, amount, (CraftFromPatternTask) task);
        if (type == ExtractItemTask.class) return extraction(requestId, (ExtractItemTask<?>) task);
        // These tasks fulfil their request once, so the entry's remaining amount is the task's amount.
        if (type == EmitItemTask.class) return leaf(requestId, ContributionKind.EMITTED, amount);
        if (type == IgnoreMissingItemTask.class || type == ConjureItemTask.class)
            return leaf(requestId, ContributionKind.MISSING, amount);
        return leaf(requestId, ContributionKind.UNKNOWN, amount);
    }

    private boolean leaf(int requestId, @NotNull ContributionKind kind, long amount) {
        return available() && capture.addContribution(requestId, kind, amount);
    }

    private boolean crafting(int requestId, long amount, @NotNull CraftFromPatternTask task) {
        Integer existing = taskIds.get(task);
        int taskId;
        if (existing == null) {
            if (!available()) return false;
            taskId = capture.addTask(task.getTotalCraftsDone());
            if (taskId < 0) return false;
            taskIds.put(task, taskId);
            pendingTasks.addLast(task);
        } else {
            taskId = existing;
        }
        return available() && capture.addCraft(requestId, amount, taskId);
    }

    /**
     * An extraction task belongs to exactly one request and records each unit it removed either from the
     * network or from byproducts of earlier crafts; the sums of those two lists equal the amounts of its
     * resolver entries. The entries themselves do not say which source supplied them, so the task is read once
     * and split by its own lists, byproducts first as the task takes them.
     */
    private boolean extraction(int requestId, @NotNull ExtractItemTask<?> task) {
        if (!extractions.add(task)) return true;
        long reused = total(task.removedFromByproducts);
        if (reused < 0) return false;
        long stored = total(task.removedFromSystem);
        if (stored < 0) return false;
        return (reused == 0 || leaf(requestId, ContributionKind.REUSED, reused))
            && (stored == 0 || leaf(requestId, ContributionKind.STORAGE, stored));
    }

    /** Sum of the list, or -1 when the scan budget ran out. */
    private long total(@NotNull List<IAEStack<?>> removed) {
        long total = 0;
        for (IAEStack<?> stack : removed) {
            if (!visit()) return -1;
            total = Math.addExact(total, stack.getStackSize());
        }
        return total;
    }

    private boolean readTask(@NotNull CraftFromPatternTask task) {
        int taskId = taskIds.get(task);
        for (IAEStack<?> input : task.pattern.getCondensedAEInputs()) {
            int resourceId = declared(input);
            if (resourceId < 0 || !available() || !capture.addInput(taskId, resourceId, input.getStackSize()))
                return false;
        }
        for (IAEStack<?> output : task.pattern.getCondensedAEOutputs()) {
            int resourceId = declared(output);
            // The task does not expose which output selected it, so no designation is made.
            if (resourceId < 0 || !available() || !capture.addOutput(taskId, resourceId, output.getStackSize(), null))
                return false;
        }
        ICraftFromPatternTaskData children = (ICraftFromPatternTaskData) task;
        for (RequestAndPerCraftAmount child : children.web$getChildRequests()) {
            if (!childRequest(taskId, child.request)) return false;
        }
        for (CraftingRequest child : children.web$getChildRecursionRequests()
            .values()) {
            if (!childRequest(taskId, child)) return false;
        }
        capture.finishTask(taskId);
        return true;
    }

    private int declared(@NotNull IAEStack<?> stack) {
        return visit() ? resource(stack) : -1;
    }

    private boolean childRequest(int taskId, @NotNull CraftingRequest child) {
        int childId = request(child);
        return childId >= 0 && available() && capture.addChildRequest(taskId, childId);
    }

    private int resource(@NotNull IAEStack<?> stack) {
        Integer existing = resources.get(stack);
        if (existing != null) return existing;
        if (!available()) return -1;
        int id = capture.addResource(() -> AE2Controller.itemIdentities.describe((IAEKey) stack));
        if (id >= 0) resources.put(stack, id);
        return id;
    }

    /** False once the retained-row budget is used up, which is reported as the entry limit. */
    private boolean available() {
        if (capture.hasCapacity()) return true;
        capture.stopAtEntryLimit();
        return false;
    }

    private boolean visit() {
        if (++scanned <= maxScanned) return true;
        scanExhausted = true;
        return false;
    }
}
