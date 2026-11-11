package pl.kuba6000.ae2webintegration.core.http.endpoint.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.crafting.CraftingDependencyGraph;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.Limit;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceGraph;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree.Kind;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree.Mode;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree.Relation;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree.State;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;

/** Builds one response from an immutable, detached capture and an optional icon-pack snapshot. */
final class CraftingTreeDocument {

    private CraftingTreeDocument() {}

    static GetCraftingPlanTree.Response from(CraftingDependencyGraph graph, @Nullable IconPack pack, String captureId) {
        return new AggregateProjection(graph, pack).document(captureId);
    }

    static GetCraftingPlanTree.Response from(CraftingTraceGraph graph, @Nullable IconPack pack, String captureId,
        Mode mode) {
        return new TraceProjection(graph, pack, mode).document(captureId);
    }

    @Desugar
    private record Definition(List<GetCraftingPlanTree.InputGroup> inputs, List<GetCraftingPlanTree.Output> outputs) {}

    private abstract static class Projection {

        final @Nullable IconMappings mappings;
        final List<GetCraftingPlanTree.Resource> resources = new ArrayList<>();
        final List<GetCraftingPlanTree.Recipe> recipes = new ArrayList<>();
        final List<GetCraftingPlanTree.Node> nodes = new ArrayList<>();
        final List<Integer> roots = new ArrayList<>();
        final Map<Definition, Integer> definitions = new HashMap<>();

        Projection(@Nullable IconPack pack) {
            mappings = pack == null ? null : new IconMappings(pack);
        }

        final int recipe(List<GetCraftingPlanTree.InputGroup> inputs, List<GetCraftingPlanTree.Output> outputs) {
            Definition definition = new Definition(inputs, outputs);
            Integer id = definitions.get(definition);
            if (id == null) {
                id = recipes.size();
                definitions.put(definition, id);
                recipes.add(new GetCraftingPlanTree.Recipe(id, inputs, outputs));
            }
            return id;
        }

        final GetCraftingPlanTree.Response response(String captureId, Mode mode,
            @Nullable GetCraftingPlanTree.Root root, Set<Limit> limits, boolean missingComplete,
            int missingResourceCount) {
            return new GetCraftingPlanTree.Response(
                ApiStatus.OK,
                new GetCraftingPlanTree.Data(
                    State.READY,
                    mode,
                    captureId,
                    root,
                    roots,
                    resources,
                    recipes,
                    nodes,
                    !limits.isEmpty() || !missingComplete,
                    limits,
                    missingComplete,
                    missingResourceCount),
                mappings);
        }
    }

    private static final class AggregateProjection extends Projection {

        private final CraftingDependencyGraph graph;
        private final CraftingRecipeCapture capture;
        private final int recipeOffset;
        private final int ingredientOffset;

        private AggregateProjection(CraftingDependencyGraph graph, @Nullable IconPack pack) {
            super(pack);
            this.graph = graph;
            capture = graph.capture();
            recipeOffset = capture.resources()
                .size();
            ingredientOffset = recipeOffset + capture.recipes()
                .size();
        }

        private GetCraftingPlanTree.Response document(String captureId) {
            addResources();
            boolean[] missingOutputs = new boolean[capture.outputs()
                .size()];
            boolean[] missingGroups = new boolean[capture.ingredients()
                .size()];
            boolean[] missingCandidates = new boolean[capture.candidates()
                .size()];
            boolean[] missingUnplaced = new boolean[capture.recipes()
                .size()];
            for (int id = 0; id < capture.resources()
                .size(); id++) mark(missingOutputs, graph.declaredProducers(id, true));
            for (int id = 0; id < capture.recipes()
                .size(); id++) mark(missingGroups, graph.ingredients(id, true));
            for (int id = 0; id < capture.ingredients()
                .size(); id++) mark(missingCandidates, graph.alternatives(id, true));
            mark(missingUnplaced, graph.unplacedRecipes(true));
            addResourceNodes(missingOutputs);
            addRecipeNodes(missingGroups);
            addIngredientNodes(missingCandidates);
            GetCraftingPlanTree.Root root = addRoots(missingUnplaced);
            return response(
                captureId,
                Mode.AGGREGATE,
                root,
                capture.limits(),
                graph.missingComplete(),
                graph.missingResourceCount());
        }

        private void addResources() {
            for (int id = 0; id < capture.resources()
                .size(); id++)
                resources.add(
                    new GetCraftingPlanTree.Resource(
                        id,
                        capture.resources()
                            .get(id),
                        mappings,
                        graph.missingAmount(id),
                        graph.isMissing(id)));
        }

        private void addResourceNodes(boolean[] missingOutputs) {
            for (int id = 0; id < capture.resources()
                .size(); id++) {
                List<GetCraftingPlanTree.Child> children = new ArrayList<>();
                for (int row : graph.declaredProducers(id)) {
                    CraftingRecipeCapture.Output output = capture.outputs()
                        .get(row);
                    children.add(
                        new GetCraftingPlanTree.Child(
                            recipeOffset + output.recipeId(),
                            Relation.DECLARED_OUTPUT,
                            Long.toString(output.amount()),
                            output.primary(),
                            missingOutputs[row]));
                }
                nodes.add(
                    new GetCraftingPlanTree.Node(id, Kind.RESOURCE, id, null, null, null, null, null, null, children));
            }
        }

        private void addRecipeNodes(boolean[] missingGroups) {
            for (int id = 0; id < capture.recipes()
                .size(); id++) {
                List<GetCraftingPlanTree.Child> children = new ArrayList<>();
                List<GetCraftingPlanTree.InputGroup> inputs = new ArrayList<>();
                for (int group : graph.ingredients(id)) {
                    CraftingRecipeCapture.Ingredient ingredient = capture.ingredients()
                        .get(group);
                    List<GetCraftingPlanTree.Candidate> candidates = new ArrayList<>();
                    for (int row : graph.alternatives(group)) {
                        CraftingRecipeCapture.Candidate candidate = capture.candidates()
                            .get(row);
                        candidates.add(
                            new GetCraftingPlanTree.Candidate(
                                candidate.resourceId(),
                                Long.toString(candidate.amount())));
                    }
                    inputs.add(new GetCraftingPlanTree.InputGroup(Long.toString(ingredient.multiplier()), candidates));
                    children.add(
                        new GetCraftingPlanTree.Child(
                            ingredientOffset + group,
                            Relation.INGREDIENT,
                            null,
                            null,
                            missingGroups[group]));
                }
                List<GetCraftingPlanTree.Output> outputs = new ArrayList<>();
                for (int row : graph.outputs(id)) {
                    CraftingRecipeCapture.Output output = capture.outputs()
                        .get(row);
                    outputs.add(
                        new GetCraftingPlanTree.Output(
                            output.resourceId(),
                            Long.toString(output.amount()),
                            output.primary()));
                }
                CraftingRecipeCapture.Recipe occurrence = capture.recipes()
                    .get(id);
                nodes.add(
                    new GetCraftingPlanTree.Node(
                        recipeOffset + id,
                        Kind.RECIPE,
                        null,
                        recipe(inputs, outputs),
                        Long.toString(occurrence.crafts()),
                        null,
                        occurrence.complete(),
                        null,
                        null,
                        children));
            }
        }

        private void addIngredientNodes(boolean[] missingCandidates) {
            for (int id = 0; id < capture.ingredients()
                .size(); id++) {
                List<GetCraftingPlanTree.Child> children = new ArrayList<>();
                for (int row : graph.alternatives(id)) {
                    CraftingRecipeCapture.Candidate candidate = capture.candidates()
                        .get(row);
                    children.add(
                        new GetCraftingPlanTree.Child(
                            candidate.resourceId(),
                            Relation.DECLARED_ALTERNATIVE,
                            Long.toString(candidate.amount()),
                            null,
                            missingCandidates[row]));
                }
                nodes.add(
                    new GetCraftingPlanTree.Node(
                        ingredientOffset + id,
                        Kind.INGREDIENT,
                        null,
                        null,
                        null,
                        Long.toString(
                            capture.ingredients()
                                .get(id)
                                .multiplier()),
                        null,
                        children.size(),
                        null,
                        children));
            }
        }

        private @Nullable GetCraftingPlanTree.Root addRoots(boolean[] missingUnplaced) {
            CraftingRecipeCapture.Amount result = graph.root();
            GetCraftingPlanTree.Root root = result == null ? null
                : new GetCraftingPlanTree.Root(
                    result.resourceId(),
                    Long.toString(result.amount()),
                    capture.simulated());
            if (result != null) roots.add(result.resourceId());
            if (!graph.unplacedRecipes()
                .isEmpty()) {
                int id = ingredientOffset + capture.ingredients()
                    .size();
                List<GetCraftingPlanTree.Child> children = new ArrayList<>();
                for (int row : graph.unplacedRecipes()) children.add(
                    new GetCraftingPlanTree.Child(
                        recipeOffset + row,
                        Relation.UNPLACED,
                        null,
                        null,
                        missingUnplaced[row]));
                nodes.add(
                    new GetCraftingPlanTree.Node(
                        id,
                        Kind.UNPLACED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        children));
                roots.add(id);
            }
            if (!graph.unlinkedMissingResources()
                .isEmpty()) {
                int id = ingredientOffset + capture.ingredients()
                    .size() + 1;
                List<GetCraftingPlanTree.Child> children = new ArrayList<>();
                for (int resource : graph.unlinkedMissingResources())
                    children.add(new GetCraftingPlanTree.Child(resource, Relation.MISSING_RESOURCE, null, null, true));
                nodes.add(
                    new GetCraftingPlanTree.Node(
                        id,
                        Kind.UNLINKED_MISSING,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        children));
                roots.add(id);
            }
            return root;
        }
    }

    private static final class TraceProjection extends Projection {

        private final CraftingTraceGraph graph;
        private final CraftingTraceCapture capture;
        private final Mode mode;
        private final int taskOffset;
        private final int contributionOffset;

        private TraceProjection(CraftingTraceGraph graph, @Nullable IconPack pack, Mode mode) {
            super(pack);
            this.graph = graph;
            this.mode = mode;
            capture = graph.capture();
            taskOffset = capture.requests()
                .size();
            contributionOffset = taskOffset + capture.tasks()
                .size();
        }

        private GetCraftingPlanTree.Response document(String captureId) {
            addResources();
            boolean[] missingContributions = new boolean[capture.contributions()
                .size()];
            boolean[] missingRequests = new boolean[capture.taskRequests()
                .size()];
            for (int id = 0; id < capture.requests()
                .size(); id++) mark(missingContributions, graph.contributions(id, true));
            for (int id = 0; id < capture.tasks()
                .size(); id++) mark(missingRequests, graph.taskRequests(id, true));
            addRequestNodes(missingContributions);
            addTaskNodes(missingRequests);
            addContributionNodes();
            int request = graph.root();
            roots.add(request);
            CraftingTraceCapture.Request root = capture.requests()
                .get(request);
            return response(
                captureId,
                mode,
                new GetCraftingPlanTree.Root(root.resourceId(), Long.toString(root.amount()), capture.simulated()),
                capture.limits(),
                graph.missingComplete(),
                graph.missingResourceCount());
        }

        private void addResources() {
            for (int id = 0; id < capture.resources()
                .size(); id++)
                resources.add(
                    new GetCraftingPlanTree.Resource(
                        id,
                        capture.resources()
                            .get(id),
                        mappings,
                        graph.missingAmount(id),
                        graph.isMissing(id)));
        }

        private void addRequestNodes(boolean[] missingContributions) {
            for (int id = 0; id < capture.requests()
                .size(); id++) {
                List<GetCraftingPlanTree.Child> children = new ArrayList<>();
                for (int row : graph.contributions(id)) {
                    CraftingTraceCapture.Contribution contribution = capture.contributions()
                        .get(row);
                    Integer task = contribution.task();
                    children.add(
                        new GetCraftingPlanTree.Child(
                            task == null ? contributionOffset + row : taskOffset + task,
                            task == null ? Relation.CONTRIBUTION : Relation.CRAFT,
                            Long.toString(contribution.amount()),
                            null,
                            missingContributions[row]));
                }
                nodes.add(
                    new GetCraftingPlanTree.Node(
                        id,
                        Kind.REQUEST,
                        capture.requests()
                            .get(id)
                            .resourceId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        children));
            }
        }

        private void addTaskNodes(boolean[] missingRequests) {
            for (int id = 0; id < capture.tasks()
                .size(); id++) {
                List<GetCraftingPlanTree.InputGroup> inputs = new ArrayList<>();
                for (int row : graph.inputs(id)) {
                    CraftingTraceCapture.Input input = capture.inputs()
                        .get(row);
                    inputs.add(
                        new GetCraftingPlanTree.InputGroup(
                            "1",
                            Collections.singletonList(
                                new GetCraftingPlanTree.Candidate(input.resourceId(), Long.toString(input.amount())))));
                }
                List<GetCraftingPlanTree.Output> outputs = new ArrayList<>();
                for (int row : graph.outputs(id)) {
                    CraftingTraceCapture.Output output = capture.outputs()
                        .get(row);
                    outputs.add(
                        new GetCraftingPlanTree.Output(
                            output.resourceId(),
                            Long.toString(output.amount()),
                            output.primary()));
                }
                List<GetCraftingPlanTree.Child> children = new ArrayList<>();
                for (int row : graph.taskRequests(id)) {
                    int request = capture.taskRequests()
                        .get(row)
                        .requestId();
                    children.add(
                        new GetCraftingPlanTree.Child(
                            request,
                            Relation.CHILD_REQUEST,
                            Long.toString(
                                capture.requests()
                                    .get(request)
                                    .amount()),
                            null,
                            missingRequests[row]));
                }
                CraftingTraceCapture.Task task = capture.tasks()
                    .get(id);
                nodes.add(
                    new GetCraftingPlanTree.Node(
                        taskOffset + id,
                        Kind.TASK,
                        null,
                        recipe(inputs, outputs),
                        Long.toString(task.crafts()),
                        null,
                        task.complete(),
                        null,
                        null,
                        children));
            }
        }

        private void addContributionNodes() {
            for (int row = 0; row < capture.contributions()
                .size(); row++) {
                CraftingTraceCapture.Contribution contribution = capture.contributions()
                    .get(row);
                if (contribution.task() == null) nodes.add(
                    new GetCraftingPlanTree.Node(
                        contributionOffset + row,
                        Kind.CONTRIBUTION,
                        contribution.resourceId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        contribution.kind(),
                        Collections.emptyList()));
            }
        }
    }

    private static void mark(boolean[] flags, List<Integer> rows) {
        for (int row : rows) flags[row] = true;
    }
}
