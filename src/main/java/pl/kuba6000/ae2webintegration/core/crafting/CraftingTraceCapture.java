package pl.kuba6000.ae2webintegration.core.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Detached per-occurrence trace of a completed calculation, as the native job recorded it. Every
 * requested occurrence of a resource is a request; the native job's answers to it are its contributions,
 * in native order. A craft contribution points at a task: a selected pattern with its craft count and
 * declared per-craft inputs and outputs, whose child requests are the ingredients it asked for. One task
 * may serve several requests; each such request keeps its own amount on its craft contribution.
 * Resource, request, contribution, task, input, output and child-request IDs index their tables. Amounts
 * are native units: requested/contributed totals for requests and contributions, per craft for inputs and
 * outputs. Nothing is split or inferred beyond what the native job recorded.
 * A null root means the trace did not reach its first request; such a capture is not a ready web plan.
 */
@Desugar
public record CraftingTraceCapture(@Nullable Integer root, boolean simulated,
    @NotNull List<ResourceDescriptor> resources, @NotNull List<Request> requests,
    @NotNull List<Contribution> contributions, @NotNull List<Task> tasks, @NotNull List<Input> inputs,
    @NotNull List<Output> outputs, @NotNull List<TaskRequest> taskRequests,
    @NotNull Set<CraftingRecipeCapture.Limit> limits) {

    /** Copies detached tables. Callers constructing directly must supply consistent local references. */
    public CraftingTraceCapture {
        resources = copy(resources);
        requests = copy(requests);
        contributions = copy(contributions);
        tasks = copy(tasks);
        inputs = copy(inputs);
        outputs = copy(outputs);
        taskRequests = copy(taskRequests);
        EnumSet<CraftingRecipeCapture.Limit> limitCopy = EnumSet.noneOf(CraftingRecipeCapture.Limit.class);
        limitCopy.addAll(limits);
        limits = Collections.unmodifiableSet(limitCopy);
    }

    private static <T> List<T> copy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** How the native job satisfied part of a request. CRAFT carries a task; the others are leaves. */
    public enum ContributionKind {
        /** Taken from the network's stored items. */
        STORAGE,
        /** Provided by an emitter pattern or other emitted source. */
        EMITTED,
        /** Not available; the calculation reports it as missing. */
        MISSING,
        /** Produced by crafting a task. */
        CRAFT,
        /** A byproduct or leftover of another craft reused inside the plan; distinct from storage. */
        REUSED,
        /** The native job recorded a source this trace cannot classify. */
        UNKNOWN
    }

    /** One requested occurrence of a resource; amount is what was requested at that point. */
    @Desugar
    public record Request(int resourceId, long amount) {}

    /** The part of a request satisfied this way; task is non-null exactly for CRAFT. */
    @Desugar
    public record Contribution(int requestId, @NotNull ContributionKind kind, long amount, @Nullable Integer task,
        @Nullable Integer resourceId) {}

    /** A selected pattern; complete once every declared input, output and child request was admitted. */
    @Desugar
    public record Task(long crafts, boolean complete) {}

    @Desugar
    public record Input(int taskId, int resourceId, long amount) {}

    /** Primary designation is true/false when known, or null when the native job does not retain it. */
    @Desugar
    public record Output(int taskId, int resourceId, long amount, @Nullable Boolean primary) {}

    /** A request the task issued for one of its ingredients, in native order. */
    @Desugar
    public record TaskRequest(int taskId, int requestId) {}

    /**
     * Single-threaded capture sink with the {@link CraftingRecipeCapture.Builder} discipline: every
     * appended row consumes one entry, descriptions consume UTF-16 characters, a rejected add returns -1 or
     * false and closes the capture, and traversal stops there. IDs are plan-local, in append order.
     * Emit in native traversal order: a request, then its contributions in order; a craft needs its task to
     * exist (addTask) before addCraft, and a child request to exist before addChildRequest.
     */
    public static final class Builder {

        private final CaptureBudget budget;
        private final List<Request> requests = new ArrayList<>();
        private final List<Contribution> contributions = new ArrayList<>();
        private final List<Task> tasks = new ArrayList<>();
        private final List<Input> inputs = new ArrayList<>();
        private final List<Output> outputs = new ArrayList<>();
        private final List<TaskRequest> taskRequests = new ArrayList<>();
        private @Nullable Integer root;
        private boolean simulated;

        public Builder(int maxEntries, int maxDescriptionCharacters) {
            budget = new CaptureBudget(maxEntries, maxDescriptionCharacters);
        }

        /** Check before native getters; this does not itself declare omitted data. */
        public boolean hasCapacity() {
            return budget.hasCapacity();
        }

        /** Report remaining native data without reading it after hasCapacity returned false. */
        public void stopAtEntryLimit() {
            budget.stopAtEntryLimit();
        }

        /** Invokes the native description supplier only after entry admission; rejection returns -1. */
        public int addResource(@NotNull Supplier<ResourceDescriptor> descriptor) {
            return budget.addResource(descriptor);
        }

        /** The requested occurrence of a resource; rejection returns -1. */
        public int addRequest(int resourceId, long amount) {
            budget.resources.get(resourceId);
            requireNonNegative(amount);
            if (!budget.admit()) return -1;
            requests.add(new Request(resourceId, amount));
            return requests.size() - 1;
        }

        /** Marks the request the calculation was started for. */
        public void root(int requestId, boolean simulation) {
            requests.get(requestId);
            root = requestId;
            simulated = simulation;
        }

        /** A leaf contribution: STORAGE, EMITTED, MISSING, REUSED or UNKNOWN. Use addCraft for CRAFT. */
        public boolean addContribution(int requestId, @NotNull CraftingTraceCapture.ContributionKind kind,
            long amount) {
            Integer resourceId = kind == ContributionKind.EMITTED || kind == ContributionKind.MISSING
                ? requests.get(requestId)
                    .resourceId()
                : null;
            return addContribution(requestId, kind, amount, resourceId);
        }

        /** A leaf contribution with the actual source resource, or null when it is unknown. */
        public boolean addContribution(int requestId, @NotNull CraftingTraceCapture.ContributionKind kind, long amount,
            @Nullable Integer resourceId) {
            if (kind == ContributionKind.CRAFT) throw new IllegalArgumentException("A craft needs its task");
            if (resourceId != null) budget.resources.get(resourceId);
            return append(requestId, kind, amount, null, resourceId);
        }

        /**
         * The part of the request satisfied by crafting this task, which may already be finished and serve other
         * requests.
         */
        public boolean addCraft(int requestId, long amount, int taskId) {
            tasks.get(taskId);
            return append(requestId, ContributionKind.CRAFT, amount, taskId, null);
        }

        private boolean append(int requestId, ContributionKind kind, long amount, @Nullable Integer task,
            @Nullable Integer resourceId) {
            requests.get(requestId);
            requireNonNegative(amount);
            if (!budget.admit()) return false;
            contributions.add(new Contribution(requestId, kind, amount, task, resourceId));
            return true;
        }

        /** A selected pattern; its declarations and child requests follow. Rejection returns -1. */
        public int addTask(long crafts) {
            requireNonNegative(crafts);
            if (!budget.admit()) return -1;
            tasks.add(new Task(crafts, false));
            return tasks.size() - 1;
        }

        /** A declared input of one craft. */
        public boolean addInput(int taskId, int resourceId, long amount) {
            requireOpen(taskId);
            budget.resources.get(resourceId);
            requireNonNegative(amount);
            if (!budget.admit()) return false;
            inputs.add(new Input(taskId, resourceId, amount));
            return true;
        }

        /** A declared output of one craft; primary is null when the native job does not retain it. */
        public boolean addOutput(int taskId, int resourceId, long amount, @Nullable Boolean primary) {
            requireOpen(taskId);
            budget.resources.get(resourceId);
            requireNonNegative(amount);
            if (!budget.admit()) return false;
            outputs.add(new Output(taskId, resourceId, amount, primary));
            return true;
        }

        /** A request the task issued for an ingredient; add the request first. */
        public boolean addChildRequest(int taskId, int requestId) {
            requireOpen(taskId);
            requests.get(requestId);
            if (!budget.admit()) return false;
            taskRequests.add(new TaskRequest(taskId, requestId));
            return true;
        }

        /** Call only after every native input, output and child request of the task was admitted. */
        public void finishTask(int taskId) {
            if (!budget.limits.isEmpty()) throw new IllegalStateException("Capture was truncated");
            requireOpen(taskId);
            tasks.set(
                taskId,
                new Task(
                    tasks.get(taskId)
                        .crafts(),
                    true));
        }

        private void requireOpen(int taskId) {
            if (tasks.get(taskId)
                .complete()) throw new IllegalStateException("Task capture already complete");
        }

        private static void requireNonNegative(long amount) {
            if (amount < 0) throw new IllegalArgumentException("Negative quantity");
        }

        public @NotNull CraftingTraceCapture build() {
            return new CraftingTraceCapture(
                root,
                simulated,
                budget.resources,
                requests,
                contributions,
                tasks,
                inputs,
                outputs,
                taskRequests,
                budget.limits);
        }
    }
}
