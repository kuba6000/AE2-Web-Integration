package pl.kuba6000.ae2webintegration.core.crafting;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Navigation through a captured trace: a request lists its contributions, a task lists the requests it
 * issued. Local capture IDs are the node references; a task serving several requests is one node reached
 * from each of them, never copied. Every list is a row view in native order, without copying.
 */
public final class CraftingTraceGraph {

    private static final int NO_TASK = -1;

    private final CraftingTraceCapture capture;
    private final int root;
    private final Adjacency contributions;
    private final Adjacency inputs;
    private final Adjacency outputs;
    private final Adjacency taskRequests;
    private final MissingIndex missing;

    private CraftingTraceGraph(CraftingTraceCapture capture) {
        Integer rootRequest = capture.root();
        if (rootRequest == null) throw new IllegalArgumentException("A trace graph needs its root request");
        root = rootRequest;
        this.capture = capture;
        contributions = new Adjacency(
            capture.requests()
                .size(),
            capture.contributions()
                .size(),
            row -> capture.contributions()
                .get(row)
                .requestId());
        inputs = new Adjacency(
            capture.tasks()
                .size(),
            capture.inputs()
                .size(),
            row -> capture.inputs()
                .get(row)
                .taskId());
        outputs = new Adjacency(
            capture.tasks()
                .size(),
            capture.outputs()
                .size(),
            row -> capture.outputs()
                .get(row)
                .taskId());
        taskRequests = new Adjacency(
            capture.tasks()
                .size(),
            capture.taskRequests()
                .size(),
            row -> capture.taskRequests()
                .get(row)
                .taskId());
        missing = new MissingIndex();
    }

    /**
     * Index a detached capture that reached its root request, with valid local references. Time and retained index size
     * are linear in
     * its already bounded tables; no table is copied.
     */
    public static @NotNull CraftingTraceGraph from(@NotNull CraftingTraceCapture capture) {
        return new CraftingTraceGraph(capture);
    }

    public @NotNull CraftingTraceCapture capture() {
        return capture;
    }

    /** The request the calculation was started for. */
    public int root() {
        return root;
    }

    /** Contribution-row IDs of the request, in native order. */
    public @NotNull List<Integer> contributions(int requestId) {
        return contributions.rowsFor(requestId);
    }

    /** Declared-input row IDs of the task. */
    public @NotNull List<Integer> inputs(int taskId) {
        return inputs.rowsFor(taskId);
    }

    /** Declared-output row IDs of the task, including captured byproducts. */
    public @NotNull List<Integer> outputs(int taskId) {
        return outputs.rowsFor(taskId);
    }

    /** Child-request row IDs of the task, in native order. */
    public @NotNull List<Integer> taskRequests(int taskId) {
        return taskRequests.rowsFor(taskId);
    }

    /** With missingOnly: only the contributions on a captured path to a MISSING contribution. */
    public @NotNull List<Integer> contributions(int requestId, boolean missingOnly) {
        return missingOnly ? missing.contributions.rowsFor(requestId) : contributions(requestId);
    }

    /** With missingOnly: only the child requests on a captured path to a MISSING contribution. */
    public @NotNull List<Integer> taskRequests(int taskId, boolean missingOnly) {
        return missingOnly ? missing.taskRequests.rowsFor(taskId) : taskRequests(taskId);
    }

    /**
     * Sum of the resource's MISSING contributions over the trace, never split by branch. Null means
     * unknown absence in an incomplete capture; zero is a known absence.
     */
    public @Nullable BigInteger missingAmount(int resourceId) {
        BigInteger amount = missing.amounts[resourceId];
        return amount == null && missing.complete ? BigInteger.ZERO : amount;
    }

    /** Whether missing absence can be established from this fully captured trace. */
    public boolean missingComplete() {
        return missing.complete;
    }

    /** Number of distinct resources with a captured positive missing amount. */
    public int missingResourceCount() {
        return missing.count;
    }

    /** Reverse dependency links only: a request to the tasks that issued it, a task to the requests crafting it. */
    private final class MissingIndex {

        private final BigInteger[] amounts = new BigInteger[capture.resources()
            .size()];
        private final boolean complete;
        private final int count;
        private final Adjacency contributions;
        private final Adjacency taskRequests;

        private MissingIndex() {
            boolean allCaptured = capture.limits()
                .isEmpty();
            for (CraftingTraceCapture.Task task : capture.tasks()) allCaptured &= task.complete();
            complete = allCaptured;
            int requestCount = capture.requests()
                .size();
            boolean[] missingRequests = new boolean[requestCount];
            for (int row = 0; row < capture.contributions()
                .size(); row++) {
                CraftingTraceCapture.Contribution contribution = capture.contributions()
                    .get(row);
                if (contribution.kind() != CraftingTraceCapture.ContributionKind.MISSING || contribution.amount() == 0)
                    continue;
                missingRequests[contribution.requestId()] = true;
                int resource = capture.requests()
                    .get(contribution.requestId())
                    .resourceId();
                BigInteger amount = BigInteger.valueOf(contribution.amount());
                amounts[resource] = amounts[resource] == null ? amount : amounts[resource].add(amount);
            }
            int knownMissing = 0;
            for (BigInteger amount : amounts) if (amount != null) knownMissing++;
            count = knownMissing;
            Adjacency issuers = new Adjacency(
                requestCount,
                capture.taskRequests()
                    .size(),
                row -> capture.taskRequests()
                    .get(row)
                    .requestId());
            // The task each craft contribution serves; other contributions have none.
            int[] craftedTask = new int[capture.contributions()
                .size()];
            for (int row = 0; row < craftedTask.length; row++) {
                Integer task = capture.contributions()
                    .get(row)
                    .task();
                craftedTask[row] = task == null ? NO_TASK : task;
            }
            Adjacency crafters = new Adjacency(
                capture.tasks()
                    .size(),
                craftedTask.length,
                row -> craftedTask[row],
                row -> craftedTask[row] != NO_TASK);
            boolean[] relevantTasks = new boolean[capture.tasks()
                .size()];
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            for (int request = 0; request < requestCount; request++)
                if (missingRequests[request]) pending.addLast(request);
            boolean[] relevantRequests = missingRequests.clone();
            while (!pending.isEmpty()) {
                for (int row : issuers.rowsFor(pending.removeFirst())) {
                    int task = capture.taskRequests()
                        .get(row)
                        .taskId();
                    if (relevantTasks[task]) continue;
                    relevantTasks[task] = true;
                    for (int craft : crafters.rowsFor(task)) {
                        int request = capture.contributions()
                            .get(craft)
                            .requestId();
                        if (!relevantRequests[request]) {
                            relevantRequests[request] = true;
                            pending.addLast(request);
                        }
                    }
                }
            }
            contributions = new Adjacency(
                requestCount,
                capture.contributions()
                    .size(),
                row -> capture.contributions()
                    .get(row)
                    .requestId(),
                row -> {
                    CraftingTraceCapture.Contribution contribution = capture.contributions()
                        .get(row);
                    return craftedTask[row] == NO_TASK
                        ? contribution.kind() == CraftingTraceCapture.ContributionKind.MISSING
                            && contribution.amount() > 0
                        : relevantTasks[craftedTask[row]];
                });
            taskRequests = new Adjacency(
                capture.tasks()
                    .size(),
                capture.taskRequests()
                    .size(),
                row -> capture.taskRequests()
                    .get(row)
                    .taskId(),
                row -> relevantRequests[capture.taskRequests()
                    .get(row)
                    .requestId()]);
        }
    }
}
