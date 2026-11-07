package pl.kuba6000.ae2webintegration.core.crafting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.ContributionKind;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Builds a deterministic occurrence view of aggregate totals, not a recorded execution trace. */
public final class AggregateTreeReconstruction {

    private AggregateTreeReconstruction() {}

    /**
     * Allocate the complete capture in root-first order, within an independent row budget. Returns
     * null when its declarations or totals cannot be accounted for without guessing. The original
     * aggregate capture remains the source of truth and can still be displayed in that case.
     */
    public static @Nullable CraftingTraceCapture from(@NotNull CraftingRecipeCapture capture, int maxEntries) {
        CraftingRecipeCapture.Amount result = capture.result();
        if (maxEntries < 0 || result == null
            || result.amount() <= 0
            || !capture.limits()
                .isEmpty())
            return null;
        try {
            return new Allocation(capture, maxEntries).run(result);
        } catch (ArithmeticException unsupportedQuantity) {
            return null;
        }
    }

    @Desugar
    private record Input(int resource, long amount) {}

    @Desugar
    private record Producer(int recipe, long amount) {}

    private static final class Recipe {

        final List<Input> inputs = new ArrayList<>();
        final List<CraftingRecipeCapture.Output> outputs = new ArrayList<>();
        final Map<Integer, Long> outputTotals = new LinkedHashMap<>();
    }

    private abstract static class Frame {
    }

    private static final class Request extends Frame {

        final int id;
        final int resource;
        final boolean root;
        long remaining;
        boolean leavesConsumed;

        Request(int id, int resource, long amount, boolean root) {
            this.id = id;
            this.resource = resource;
            this.root = root;
            remaining = amount;
        }
    }

    private static final class Task extends Frame {

        final int id;
        final int recipe;
        final long crafts;
        final int requestedResource;
        final long reserved;
        int nextInput;

        Task(int id, int recipe, long crafts, int requestedResource, long reserved) {
            this.id = id;
            this.recipe = recipe;
            this.crafts = crafts;
            this.requestedResource = requestedResource;
            this.reserved = reserved;
        }
    }

    private static final class Allocation {

        final CraftingRecipeCapture capture;
        final CraftingTraceCapture.Builder trace;
        final List<Recipe> recipes = new ArrayList<>();
        final List<List<Producer>> producers = new ArrayList<>();
        final long[] remainingCrafts;
        final boolean[] activeRecipes;
        final int[] nextProducer;
        final long[] initial;
        final long[] emitted;
        final long[] missing;
        final long[] surplus;
        final ArrayDeque<Frame> stack = new ArrayDeque<>();

        Allocation(CraftingRecipeCapture capture, int maxEntries) {
            this.capture = capture;
            trace = new CraftingTraceCapture.Builder(maxEntries, Integer.MAX_VALUE);
            int resourceCount = capture.resources()
                .size();
            for (int i = 0; i < resourceCount; i++) producers.add(new ArrayList<>());
            remainingCrafts = new long[capture.recipes()
                .size()];
            activeRecipes = new boolean[remainingCrafts.length];
            nextProducer = new int[resourceCount];
            initial = new long[resourceCount];
            emitted = new long[resourceCount];
            missing = new long[resourceCount];
            surplus = new long[resourceCount];
        }

        @Nullable
        CraftingTraceCapture run(CraftingRecipeCapture.Amount result) {
            if (!index() || !copyResources()) return null;
            int root = trace.addRequest(result.resourceId(), result.amount());
            if (root < 0) return null;
            trace.root(root, capture.simulated());
            stack.push(new Request(root, result.resourceId(), result.amount(), true));
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (frame instanceof Request) {
                    if (!advance((Request) frame)) return null;
                } else if (!advance((Task) frame)) return null;
            }
            for (long crafts : remainingCrafts) if (crafts != 0) return null;
            if (!empty(initial) || !empty(emitted) || !empty(missing)) return null;
            return trace.build();
        }

        private boolean index() {
            Adjacency groups = new Adjacency(
                capture.recipes()
                    .size(),
                capture.ingredients()
                    .size(),
                row -> capture.ingredients()
                    .get(row)
                    .recipeId());
            Adjacency candidates = new Adjacency(
                capture.ingredients()
                    .size(),
                capture.candidates()
                    .size(),
                row -> capture.candidates()
                    .get(row)
                    .ingredientId());
            Adjacency outputs = new Adjacency(
                capture.recipes()
                    .size(),
                capture.outputs()
                    .size(),
                row -> capture.outputs()
                    .get(row)
                    .recipeId());
            for (int id = 0; id < remainingCrafts.length; id++) {
                CraftingRecipeCapture.Recipe source = capture.recipes()
                    .get(id);
                if (!source.complete() || source.crafts() < 0) return false;
                remainingCrafts[id] = source.crafts();
                Recipe recipe = new Recipe();
                for (int group : groups.rowsFor(id)) {
                    CraftingRecipeCapture.Ingredient ingredient = capture.ingredients()
                        .get(group);
                    List<Integer> alternatives = candidates.rowsFor(group);
                    if (ingredient.multiplier() <= 0 || alternatives.size() != 1) return false;
                    CraftingRecipeCapture.Candidate candidate = capture.candidates()
                        .get(alternatives.get(0));
                    if (candidate.amount() <= 0) return false;
                    long amount = Math.multiplyExact(ingredient.multiplier(), candidate.amount());
                    recipe.inputs.add(new Input(candidate.resourceId(), amount));
                }
                for (int row : outputs.rowsFor(id)) {
                    CraftingRecipeCapture.Output output = capture.outputs()
                        .get(row);
                    if (output.amount() <= 0) return false;
                    recipe.outputs.add(output);
                    long total = Math
                        .addExact(recipe.outputTotals.getOrDefault(output.resourceId(), 0L), output.amount());
                    recipe.outputTotals.put(output.resourceId(), total);
                }
                recipes.add(recipe);
                for (Map.Entry<Integer, Long> output : recipe.outputTotals.entrySet()) producers.get(output.getKey())
                    .add(new Producer(id, output.getValue()));
            }
            for (CraftingRecipeCapture.Summary summary : capture.summaries()) {
                if (summary.amount() < 0) return false;
                long[] pool = pool(summary.kind());
                int resource = summary.resourceId();
                pool[resource] = Math.addExact(pool[resource], summary.amount());
            }
            return true;
        }

        private long[] pool(SummaryKind kind) {
            switch (kind) {
                case INITIAL:
                    return initial;
                case EMITTED:
                    return emitted;
                case MISSING:
                    return missing;
                default:
                    throw new AssertionError(kind);
            }
        }

        private boolean copyResources() {
            for (ResourceDescriptor descriptor : capture.resources())
                if (trace.addResource(() -> descriptor) < 0) return false;
            return true;
        }

        private boolean advance(Request request) {
            if (!request.leavesConsumed) {
                request.leavesConsumed = true;
                if ((!request.root && !take(request, initial, ContributionKind.STORAGE))
                    || !take(request, surplus, ContributionKind.REUSED)
                    || !take(request, emitted, ContributionKind.EMITTED)) return false;
            } else if (!take(request, surplus, ContributionKind.REUSED)) return false;
            if (request.remaining == 0) {
                stack.pop();
                return true;
            }
            List<Producer> choices = producers.get(request.resource);
            int next = nextProducer[request.resource];
            while (next < choices.size() && remainingCrafts[choices.get(next).recipe] == 0) next++;
            nextProducer[request.resource] = next;
            if (next == choices.size()) {
                if (!take(request, missing, ContributionKind.MISSING) || request.remaining != 0) return false;
                stack.pop();
                return true;
            }
            Producer producer = choices.get(next);
            if (activeRecipes[producer.recipe]) return false;
            long output = producer.amount;
            long needed = request.remaining / output + (request.remaining % output == 0 ? 0 : 1);
            long crafts = Math.min(needed, remainingCrafts[producer.recipe]);
            remainingCrafts[producer.recipe] -= crafts;
            long produced = Math.multiplyExact(output, crafts);
            long supplied = Math.min(request.remaining, produced);
            int task = trace.addTask(crafts);
            if (task < 0 || !trace.addCraft(request.id, supplied, task)) return false;
            Recipe recipe = recipes.get(producer.recipe);
            for (Input input : recipe.inputs) if (!trace.addInput(task, input.resource, input.amount)) return false;
            for (CraftingRecipeCapture.Output row : recipe.outputs)
                if (!trace.addOutput(task, row.resourceId(), row.amount(), row.primary())) return false;
            request.remaining -= supplied;
            activeRecipes[producer.recipe] = true;
            stack.push(new Task(task, producer.recipe, crafts, request.resource, supplied));
            return true;
        }

        private boolean take(Request request, long[] pool, ContributionKind kind) {
            long amount = Math.min(request.remaining, pool[request.resource]);
            if (amount == 0) return true;
            if (!trace.addContribution(request.id, kind, amount, request.resource)) return false;
            pool[request.resource] -= amount;
            request.remaining -= amount;
            return true;
        }

        private boolean advance(Task task) {
            Recipe recipe = recipes.get(task.recipe);
            if (task.nextInput < recipe.inputs.size()) {
                Input input = recipe.inputs.get(task.nextInput++);
                long amount = Math.multiplyExact(task.crafts, input.amount);
                int request = trace.addRequest(input.resource, amount);
                if (request < 0 || !trace.addChildRequest(task.id, request)) return false;
                stack.push(new Request(request, input.resource, amount, false));
                return true;
            }
            trace.finishTask(task.id);
            for (Map.Entry<Integer, Long> output : recipe.outputTotals.entrySet()) {
                int resource = output.getKey();
                long produced = Math.multiplyExact(output.getValue(), task.crafts);
                if (resource == task.requestedResource) produced -= task.reserved;
                surplus[resource] = Math.addExact(surplus[resource], produced);
            }
            activeRecipes[task.recipe] = false;
            stack.pop();
            return true;
        }

        private static boolean empty(long[] pool) {
            for (long amount : pool) if (amount != 0) return false;
            return true;
        }
    }
}
