package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.CraftingJob;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingTreeNodeData;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingTreeProcessData;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.ContributionKind;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/**
 * Captures the per-path trace of a completed UEL calculation. The tree is unshared: every
 * CraftingTreeNode is one requested path and every CraftingTreeProcess below it a candidate pattern, so a
 * node becomes a request and a process that crafted becomes a task of its own. Counters are read as
 * {@code setJob} reads them (see {@link RecipePlanCapture}), in the order {@code CraftingTreeNode.request}
 * answers a request: stored amounts, emitted amount, crafts, missing amount.
 * <p>
 * The tree records no requested amount per node. The root's is the job output, which {@code CraftingJob.run}
 * passes to the first request. A child's is what its process asked for, the per-craft amount in the process's
 * node map times the process's crafts, which is how {@code CraftingTreeProcess.request} multiplies it. A
 * craft contribution is what the task produces of the node's item, crafts times its per-craft output; batch
 * rounding can make it exceed the request, and byproduct reuse out of the working inventory is not recorded
 * by the tree at all, so no REUSED contribution exists. Stored amounts count only what the job took from the
 * network; a fuzzy request can store a different item than it asked for, so each storage contribution names
 * the actual stack that was used.
 * <p>
 * A process that crafted nothing cannot be a task. Its children are normally untouched, but counters left by a
 * failed alternative would make the trace disagree with what {@code setJob} submits, so the trace is then
 * unavailable and the aggregate capture, which sums those counters, applies.
 */
public final class TraceCapture {

    private final CraftingTraceCapture.Builder capture;
    private final long maxVisits;
    private final Map<IAEItemStack, Integer> resources = new HashMap<>();
    private final Queue<Occurrence> pending = new ArrayDeque<>();
    private long visited;
    private boolean unavailable;

    private TraceCapture(int maxEntries, int maxDescriptionCharacters) {
        capture = new CraftingTraceCapture.Builder(maxEntries, maxDescriptionCharacters);
        maxVisits = (long) maxEntries * RecipePlanCapture.SCAN_VISITS_PER_ENTRY;
    }

    /**
     * Caller owns the completed job and invokes this on the server thread. Returns null when the scan reads
     * more than 50 times maxEntries nodes, processes and used entries, or meets counters that no submitted
     * process owns. Retained rows are bounded by maxEntries and reported as truncation by the builder.
     */
    public static @Nullable CraftingTraceCapture capture(@NotNull CraftingJob job, int maxEntries,
        int maxDescriptionCharacters) {
        TraceCapture reader = new TraceCapture(maxEntries, maxDescriptionCharacters);
        reader.read(job);
        return reader.unavailable ? null : reader.capture.build();
    }

    private void read(@NotNull CraftingJob job) {
        if (!available()) return;
        IAEItemStack output = job.getOutput();
        int resourceId = resource(output);
        if (resourceId < 0) return;
        int root = capture.addRequest(resourceId, output.getStackSize());
        if (root < 0) return;
        capture.root(root, job.isSimulation());
        pending.add(new Occurrence(job.getTree(), root));
        boolean more = true;
        while (more && !pending.isEmpty()) more = occurrence(pending.remove());
    }

    /** Returns false when the capture is closed or unavailable; traversal stops there. */
    private boolean occurrence(@NotNull Occurrence occurrence) {
        if (visitExceedsBudget()) return abandon();
        ICraftingTreeNodeData node = nodeData(occurrence.node());
        int request = occurrence.request();
        for (IAEItemStack used : node.web$getUsed()) {
            if (visitExceedsBudget()) return abandon();
            if (!stored(request, used)) return false;
        }
        if (!contribute(request, ContributionKind.EMITTED, node.web$getHowManyEmitted())) return false;
        for (CraftingTreeProcess process : node.web$getProcesses()) {
            if (visitExceedsBudget()) return abandon();
            ICraftingTreeProcessData data = processData(process);
            if (data.web$getCrafts() > 0) {
                if (!craft(request, node.web$getWhat(), data)) return false;
            } else if (!holdsNoCounters(data)) {
                return abandon();
            }
        }
        return contribute(request, ContributionKind.MISSING, node.web$getMissing());
    }

    private boolean stored(int request, @NotNull IAEItemStack stack) {
        long amount = stack.getStackSize();
        if (amount <= 0) return true;
        if (!available()) return false;
        int resourceId = resource(stack);
        return resourceId >= 0 && available()
            && capture.addContribution(request, ContributionKind.STORAGE, amount, resourceId);
    }

    /** Skips non-positive amounts: the tree keeps zeroed entries after resets and refunds. */
    private boolean contribute(int request, @NotNull ContributionKind kind, long amount) {
        if (amount <= 0) return true;
        if (!available()) return false;
        return capture.addContribution(request, kind, amount);
    }

    private boolean craft(int request, @NotNull IAEItemStack what, @NotNull ICraftingTreeProcessData process) {
        if (!available()) return false;
        long crafts = process.web$getCrafts();
        IAEItemStack crafted = process.web$getAmountCrafted(what);
        int task = capture.addTask(crafts);
        if (task < 0 || !capture.addCraft(request, Math.multiplyExact(crafts, crafted.getStackSize()), task))
            return false;
        for (Object2LongMap.Entry<CraftingTreeNode> input : process.web$getNodes()
            .object2LongEntrySet()) {
            if (!ingredient(task, input.getKey(), input.getLongValue(), crafts)) return false;
        }
        if (!available()) return false;
        ICraftingPatternDetails details = process.web$getDetails();
        for (IAEItemStack output : details.getCondensedOutputs()) {
            int resourceId = resource(output);
            if (resourceId < 0
                || !capture.addOutput(task, resourceId, output.getStackSize(), output.isSameType(crafted)))
                return false;
        }
        capture.finishTask(task);
        return true;
    }

    /** One node of the process's map: a declared per-craft input and the request issued for it. */
    private boolean ingredient(int task, @NotNull CraftingTreeNode child, long perCraft, long crafts) {
        int resourceId = resource(nodeData(child).web$getWhat());
        if (resourceId < 0) return false;
        int request = capture.addRequest(resourceId, Math.multiplyExact(perCraft, crafts));
        if (request < 0) return false;
        pending.add(new Occurrence(child, request));
        return capture.addInput(task, resourceId, perCraft) && capture.addChildRequest(task, request);
    }

    /** True when nothing below an uncrafted process holds a counter; false also when the scan budget ends. */
    private boolean holdsNoCounters(@NotNull ICraftingTreeProcessData process) {
        Deque<ICraftingTreeProcessData> stack = new ArrayDeque<>();
        stack.push(process);
        while (!stack.isEmpty()) {
            ICraftingTreeProcessData current = stack.pop();
            if (current.web$getCrafts() > 0) return false;
            for (CraftingTreeNode child : current.web$getNodes()
                .keySet()) {
                if (visitExceedsBudget()) return false;
                ICraftingTreeNodeData node = nodeData(child);
                if (node.web$getMissing() > 0 || node.web$getHowManyEmitted() > 0) return false;
                for (IAEItemStack used : node.web$getUsed()) {
                    if (visitExceedsBudget() || used.getStackSize() > 0) return false;
                }
                for (CraftingTreeProcess next : node.web$getProcesses()) {
                    if (visitExceedsBudget()) return false;
                    stack.push(processData(next));
                }
            }
        }
        return true;
    }

    private boolean visitExceedsBudget() {
        return ++visited > maxVisits;
    }

    private boolean abandon() {
        unavailable = true;
        return false;
    }

    private static @NotNull ICraftingTreeNodeData nodeData(@NotNull CraftingTreeNode node) {
        return (ICraftingTreeNodeData) (Object) node;
    }

    private static @NotNull ICraftingTreeProcessData processData(@NotNull CraftingTreeProcess process) {
        return (ICraftingTreeProcessData) (Object) process;
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

    @Desugar
    private record Occurrence(@NotNull CraftingTreeNode node, int request) {}
}
