package pl.kuba6000.ae2webintegration.core.http.endpoint.crafting;

import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingDependencyGraph;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceGraph;
import pl.kuba6000.ae2webintegration.core.grid.CraftingPlanRegistry;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceDescription;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Browse the dependencies of a retained calculated plan. Capture is lazy and shared by viewers. The response
 * {@code mode} says what the native job recorded.
 * <p>
 * AGGREGATE: links describe recipe declarations, never selected substitutes or per-consumer material
 * allocation, and the tree is an approximation. A recipe.s children are only its ingredient groups; its declared
 * outputs travel on the recipe node itself (see {@link Node}), and an ingredient group.s children are its
 * declared candidates.
 * <p>
 * TRACE: the tree mirrors the native job. A REQUEST lists, in native order, its contributions: leaf
 * CONTRIBUTION nodes (STORAGE, EMITTED, MISSING, REUSED, UNKNOWN) and TASK nodes for crafts; a TASK carries its
 * crafts, declared inputs and outputs and lists the REQUEST nodes it issued for its ingredients. Each link states
 * the amount of its request or contribution. A task serving several requests is one node reached from each.
 * With {@code missingOnly}, pages keep the paths to MISSING contributions.
 * <p>
 * Once a plan has been captured, pages are served without waiting for the server thread. A request with
 * {@code nodes} is a batched read: it returns the pages of the listed nodes and then, breadth-first, the
 * pages of the nodes they reference, until the {@code rows} budget is spent. Every page costs its child
 * count, or one row when it has no children. A node already read in the response, or reported in
 * {@code loaded}, is never read again, so shared nodes and cycles stay references. A page cut short by
 * the budget carries {@code nextOffset}; continue it with {@code KIND:id:offset} in a later request.
 * Pages for requested nodes beyond the budget are omitted and can be requested again. Batched data holds
 * {@code pages}; its single-page fields {@code node}, {@code children} and {@code nextOffset} stay empty.
 *
 * @pathParam gridKey Persistent network identifier.
 * @pathParam planId Retained crafting plan identifier.
 * @response 200 {@link Response} Preparation state or a bounded declaration page.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid node, offset or page limit.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication expired.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: grid access revoked.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND or INVALID_ID: grid or retained plan unavailable.
 * @response 409 {@link ErrorResponse} UPDATE_CHANGED: captureId belongs to a previous retained plan.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: capture or preparation failed.
 * @response 503 {@link ErrorResponse} SERVER_BUSY, SERVER_STOPPING or TIMEOUT.
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/crafting-plans/{planId}/tree")
public final class GetCraftingPlanTree extends ISyncedRequest {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    // Preview admission limits bound retained data, not the execution time of a native getter.
    private static final int MAX_CAPTURE_ENTRIES = 20_000;
    private static final int MAX_DESCRIPTION_CHARACTERS = 262_144;
    private static final int MAX_PAGE_CHILDREN = 100;
    private static final int DEFAULT_BATCH_ROWS = 500;
    private static final int MAX_BATCH_ROWS = 1000;
    private static final int MAX_BATCH_NODES = 64;
    private static final int MAX_LOADED_CHARACTERS = 8192;

    public enum Kind {
        ROOT,
        RESOURCE,
        RECIPE,
        INGREDIENT,
        UNPLACED,
        UNLINKED_MISSING,
        /** TRACE only: one requested occurrence of a resource. */
        REQUEST,
        /** TRACE only: a selected pattern; shared by every request it serves. */
        TASK,
        /** TRACE only: a leaf answering a request from storage, emission, reuse, a shortage or an unknown source. */
        CONTRIBUTION
    }

    /**
     * TRACE: the native job recorded each request and what satisfied it, so the tree is exact. AGGREGATE: only the
     * recipes selected per pattern and plan totals were recorded, so the tree is an approximation.
     */
    public enum Mode {
        TRACE,
        AGGREGATE
    }

    public enum State {
        CALCULATING,
        PREPARING,
        UNAVAILABLE,
        READY
    }

    public enum Relation {
        DECLARED_OUTPUT,
        INGREDIENT,
        DECLARED_ALTERNATIVE,
        MISSING_RESOURCE,
        UNPLACED,
        /** TRACE: a request to a leaf contribution. */
        CONTRIBUTION,
        /** TRACE: a request to the task that crafted part of it. */
        CRAFT,
        /** TRACE: a task to a request it issued for one of its ingredients. */
        CHILD_REQUEST
    }

    @PathParam("planId")
    int planId;
    /** ROOT, or a node kind of the plan's mode. ROOT resolves the calculated output (TRACE: the root request). */
    @QueryParam("kind")
    String requestedKind = "ROOT";
    private Kind kind = Kind.ROOT;
    /** Capture-local node identifier; ignored for ROOT. Synthetic UNPLACED/UNLINKED_MISSING groups use zero. */
    @QueryParam("id")
    int nodeId;
    /** Offset within the requested adjacency list. */
    @QueryParam("offset")
    int offset;
    /** Child count, from one through 100; defaults to 50. */
    @QueryParam("limit")
    int limit = 50;
    /** Optional expected capture identity; stale identities return UPDATE_CHANGED before reading node IDs. */
    @QueryParam("captureId")
    @Nullable
    String expectedCaptureId;
    /** Include available response-local atlas references. */
    @QueryParam("icons")
    boolean icons;
    /** Keep captured dependency paths to known missing resources; does not infer selected inputs. */
    @QueryParam("missingOnly")
    boolean missingOnly;
    /**
     * Batched read: up to 64 comma-separated start nodes, each {@code KIND:id} or {@code KIND:id:offset} (the
     * offset continues a page cut short by an earlier budget). Kind is RESOURCE, RECIPE, INGREDIENT, UNPLACED or
     * UNLINKED_MISSING for AGGREGATE plans and REQUEST or TASK for TRACE plans. Replaces kind, id, offset and limit.
     */
    @QueryParam("nodes")
    @Nullable
    String requestedNodes;
    /** Batched read: child-row budget of the whole response, from one through 1000; defaults to 500. */
    @QueryParam("rows")
    int rowBudget = DEFAULT_BATCH_ROWS;
    /**
     * Batched read: nodes whose pages the caller already holds completely, so they are not read again. Groups
     * are separated by {@code |}, each {@code KIND:ranges} such as {@code RECIPE:0-12,15|RESOURCE:3} or
     * {@code TASK:4|REQUEST:0-9}. Optional;
     * a hint only, at most 8192 characters.
     */
    @QueryParam("loaded")
    @Nullable
    String loadedNodes;
    private @Nullable List<Start> starts;
    private final Map<Kind, BitSet> loaded = new EnumMap<>(Kind.class);
    private @Nullable CraftingPlanRegistry.Entry entry;
    private @Nullable CraftingPlanRegistry.TreePreparation preparation;

    @Desugar
    public record Root(int resourceId, @NotNull String quantity, boolean simulated) {}

    /**
     * One output declared by a recipe, amount per craft. Primary is true or false when the native plan retains
     * the designation (false marks a byproduct) and null when it does not.
     */
    @Desugar
    public record Output(int resourceId, @NotNull String quantity, @Nullable Boolean primary) {}

    /** One input declared by a TRACE task, amount per craft. */
    @Desugar
    public record Input(int resourceId, @NotNull String quantity) {}

    /**
     * A graph node. Crafts and complete belong to recipes and TRACE tasks, whose outputs list every declared output
     * (their descriptors are in the response.s resources); tasks also list their declared inputs. Multiplier and
     * candidates belong to ingredient groups; candidates is the declared candidate count even when the
     * missing-only filter narrows the group.s page. A TRACE request and its contributions carry the requested
     * resource, and a contribution leaf its kind (never CRAFT, which is the task node itself); the amounts of
     * both travel on the child link that reaches them. Fields that do not apply to the node kind are null.
     */
    @Desugar
    public record Node(@NotNull Kind kind, int id, @Nullable String crafts, @Nullable String multiplier,
        @Nullable Boolean complete, @Nullable Integer candidates, @Nullable List<Output> outputs,
        @Nullable List<Input> inputs, @Nullable Integer resourceId,
        @Nullable CraftingTraceCapture.ContributionKind contribution) {}

    @Desugar
    public record Child(@NotNull Relation relation, @NotNull Node node, @Nullable String quantity,
        @Nullable Boolean primary) {}

    /** A page-local resource descriptor; ID refers to the retained capture, icons to this response only. */
    public static final class Resource extends ResourceDescription {

        public final int id;
        /** Captured plan-level missing amount; null when absence is unknown in an incomplete capture. */
        public final @Nullable String missingAmount;

        private Resource(int id, ResourceDescriptor descriptor, @Nullable IconMappings mappings,
            @Nullable BigInteger missing) {
            super(descriptor, mappings);
            this.id = id;
            missingAmount = missing == null ? null : missing.toString();
        }
    }

    /** The children of one node from the given offset; nextOffset continues a page cut short by its bound. */
    @Desugar
    public record Page(@NotNull Node node, int offset, @NotNull List<Child> children, @Nullable Integer nextOffset) {}

    @Desugar
    private record Start(@NotNull Kind kind, int id, int offset) {}

    /** Single-node reads fill node, children and nextOffset; batched reads fill pages and leave those empty. */
    @Desugar
    public record Data(@NotNull State state, @Nullable Mode mode, @Nullable String captureId, @Nullable Root root,
        @Nullable Node node, @NotNull List<Child> children, @NotNull List<Resource> resources,
        @Nullable Integer nextOffset, boolean partial, @NotNull Set<CraftingRecipeCapture.Limit> limits,
        int unplacedCount, boolean missingOnly, boolean missingComplete, int missingResourceCount,
        int unlinkedMissingCount, @Nullable List<Page> pages) {}

    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull Data data, @Nullable IconMappings icons)
        implements IResponseBody {}

    @Override
    public boolean init(RequestContext context) {
        if (!super.init(context)) return false;
        try {
            kind = Kind.valueOf(requestedKind);
        } catch (IllegalArgumentException invalid) {
            deny(ApiStatus.BAD_PARAM);
            return false;
        }
        if (nodeId < 0 || offset < 0
            || kind == Kind.CONTRIBUTION
            || limit < 1
            || limit > MAX_PAGE_CHILDREN
            || ((kind == Kind.UNPLACED || kind == Kind.UNLINKED_MISSING) && nodeId != 0)
            || (expectedCaptureId != null && expectedCaptureId.length() > 64)
            || rowBudget < 1
            || rowBudget > MAX_BATCH_ROWS) {
            deny(ApiStatus.BAD_PARAM);
            return false;
        }
        try {
            if (requestedNodes != null) {
                starts = parseStarts(requestedNodes);
                if (loadedNodes != null) parseLoaded(loadedNodes);
            } else if (loadedNodes != null) throw new IllegalArgumentException("loaded requires nodes");
        } catch (IllegalArgumentException invalid) {
            deny(ApiStatus.BAD_PARAM);
            return false;
        }
        return true;
    }

    private static List<Start> parseStarts(String text) {
        String[] tokens = text.split(",", -1);
        if (tokens.length > MAX_BATCH_NODES) throw new IllegalArgumentException("Too many nodes");
        Map<String, Start> unique = new LinkedHashMap<>();
        for (String token : tokens) {
            String[] parts = token.split(":", -1);
            if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException("Malformed node");
            Kind kind = Kind.valueOf(parts[0]);
            int id = parseId(parts[1]);
            if (kind == Kind.ROOT || kind == Kind.CONTRIBUTION
                || ((kind == Kind.UNPLACED || kind == Kind.UNLINKED_MISSING) && id != 0))
                throw new IllegalArgumentException("Unsupported node");
            unique.putIfAbsent(kind + ":" + id, new Start(kind, id, parts.length == 3 ? parseId(parts[2]) : 0));
        }
        return new ArrayList<>(unique.values());
    }

    private void parseLoaded(String text) {
        if (text.length() > MAX_LOADED_CHARACTERS) throw new IllegalArgumentException("Loaded hint too long");
        for (String group : text.split("\\|", -1)) {
            int separator = group.indexOf(':');
            if (separator < 0) throw new IllegalArgumentException("Malformed loaded group");
            Kind kind = Kind.valueOf(group.substring(0, separator));
            if (kind != Kind.RESOURCE && kind != Kind.RECIPE
                && kind != Kind.INGREDIENT
                && kind != Kind.REQUEST
                && kind != Kind.TASK) throw new IllegalArgumentException("Unsupported loaded kind");
            BitSet ids = loaded.computeIfAbsent(kind, ignored -> new BitSet());
            for (String range : group.substring(separator + 1)
                .split(",", -1)) {
                String[] bounds = range.split("-", -1);
                if (bounds.length > 2) throw new IllegalArgumentException("Malformed range");
                int from = parseId(bounds[0]);
                int to = bounds.length == 2 ? parseId(bounds[1]) : from;
                if (to < from) throw new IllegalArgumentException("Reversed range");
                ids.set(from, to + 1);
            }
        }
    }

    /** Decimal capture-local number; ids and offsets never exceed the capture admission bound. */
    private static int parseId(String text) {
        if (text.isEmpty() || text.length() > 5
            || !text.chars()
                .allMatch(Character::isDigit))
            throw new IllegalArgumentException("Malformed number");
        return Integer.parseInt(text);
    }

    /**
     * A plan that already holds its captured graph is read from stored data only: the entry registry, grid
     * identity bindings and permission maps are thread-safe, and the immutable graph is indexed on the worker.
     * Anything else, including every denial that is not yet certain, takes the server stage.
     */
    @Override
    public boolean serveWithoutServerStage() {
        IAEGrid live = CoreEngine.GRID_IDENTITIES.getGrid(gridKey);
        if (!GridAccess.allows(live, context.getPrincipal())) return false;
        GridData data = GridData.find(gridKey);
        CraftingPlanRegistry.Entry found = data == null ? null : data.getJob(planId);
        CraftingPlanRegistry.TreePreparation captured = found == null ? null : found.tree();
        if (captured == null || !captured.isCaptured()) return false;
        grid = live;
        gridData = data;
        entry = found;
        preparation = captured;
        return true;
    }

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        entry = gridData.getJob(planId);
        if (entry == null) {
            deny(ApiStatus.INVALID_ID);
            return;
        }
        ApiStatus initialFailure = accessFailure();
        if (initialFailure != null) {
            deny(initialFailure);
            return;
        }
        CraftingPlanRegistry.TreePreparation existing = entry.tree();
        if (expectedCaptureId != null && (existing == null || !expectedCaptureId.equals(existing.id()))) {
            deny(ApiStatus.UPDATE_CHANGED);
            return;
        }
        try {
            preparation = entry.captureTree(MAX_CAPTURE_ENTRIES, MAX_DESCRIPTION_CHARACTERS);
            readyForWorker();
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            deny(ApiStatus.SERVER_STOPPING);
        } catch (ExecutionException | RuntimeException failure) {
            LOG.error("Failed to capture crafting dependencies", failure);
            deny(ApiStatus.INTERNAL_ERROR);
        }
    }

    @Override
    public ApiResponse finishResponse() {
        if (isResponseComplete() || entry == null) return getResponse();
        ApiStatus failure = accessFailure();
        if (failure != null) {
            deny(failure);
            return getResponse();
        }
        try {
            if (expectedCaptureId != null && (preparation == null || !expectedCaptureId.equals(preparation.id()))) {
                deny(ApiStatus.UPDATE_CHANGED);
                return getResponse();
            }
            Response body;
            if (preparation == null) body = state(State.CALCULATING);
            else if (!preparation.isCaptured()) body = state(State.PREPARING);
            else {
                CraftingTraceGraph trace = preparation.traceGraph();
                CraftingDependencyGraph aggregate = trace == null ? preparation.graph() : null;
                if (trace == null && aggregate == null) body = state(State.UNAVAILABLE);
                else {
                    Projection projection = trace == null ? new AggregateProjection(aggregate)
                        : new TraceProjection(trace, trace.root());
                    List<Start> batched = starts;
                    body = batched == null ? page(projection, preparation.id())
                        : batch(projection, preparation.id(), batched);
                }
                if (body == null) {
                    deny(ApiStatus.BAD_PARAM);
                    return getResponse();
                }
            }
            ApiResponse response = ApiResponse.of(HttpURLConnection.HTTP_OK, body);
            failure = accessFailure();
            if (failure == null) respondPrepared(response);
            else deny(failure);
        } catch (RuntimeException unexpected) {
            LOG.error("Failed to prepare crafting dependency page", unexpected);
            deny(ApiStatus.INTERNAL_ERROR);
        }
        return getResponse();
    }

    private @Nullable ApiStatus accessFailure() {
        if (!context.isLifecycleUnchanged()) return ApiStatus.SERVER_STOPPING;
        if (context.getSession() != null && !AuthService.isValid(context.getSession(), System.currentTimeMillis()))
            return ApiStatus.UNAUTHORIZED;
        if (CoreEngine.GRID_IDENTITIES.getGrid(gridKey) != grid) return ApiStatus.GRID_NOT_FOUND;
        if (!GridAccess.allows(grid, context.getPrincipal())) return ApiStatus.NO_PERMISSIONS;
        if (GridData.find(gridKey) != gridData || entry == null || !entry.isRetained()) return ApiStatus.INVALID_ID;
        return null;
    }

    private Response state(State state) {
        return new Response(
            ApiStatus.OK,
            new Data(
                state,
                null,
                preparation == null ? null : preparation.id(),
                null,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                false,
                Collections.emptySet(),
                0,
                missingOnly,
                false,
                0,
                0,
                null),
            null);
    }

    private @Nullable Response page(Projection projection, String captureId) {
        Start root = projection.rootStart();
        Kind resolvedKind = kind == Kind.ROOT ? root.kind() : kind;
        int resolvedId = kind == Kind.ROOT ? root.id() : nodeId;
        if (projection.isOutside(resolvedKind, resolvedId)) return null;
        List<Integer> adjacent = projection.adjacent(resolvedKind, resolvedId);
        if (offset > adjacent.size()) return null;
        Page page = projection.page(resolvedKind, resolvedId, adjacent, offset, limit);
        return projection.respond(captureId, page.node(), page.children(), page.nextOffset(), null);
    }

    /** Pages of the requested nodes, then breadth-first of the nodes they reference, within the row budget. */
    private @Nullable Response batch(Projection projection, String captureId, List<Start> requested) {
        for (Start start : requested) {
            if (projection.isOutside(start.kind(), start.id())
                || start.offset() > projection.adjacent(start.kind(), start.id())
                    .size())
                return null;
        }
        ArrayDeque<Start> queue = new ArrayDeque<>(requested);
        Set<Long> seen = new HashSet<>();
        for (Start start : requested) seen.add(identity(start.kind(), start.id()));
        List<Page> pages = new ArrayList<>();
        int budget = rowBudget;
        while (budget > 0 && !queue.isEmpty()) {
            Start start = queue.poll();
            Page page = projection
                .page(start.kind(), start.id(), projection.adjacent(start.kind(), start.id()), start.offset(), budget);
            pages.add(page);
            budget -= Math.max(
                1,
                page.children()
                    .size());
            for (Child child : page.children()) {
                Node next = child.node();
                BitSet known = loaded.get(next.kind());
                // A contribution is a leaf: it has no page to read.
                if (next.kind() != Kind.CONTRIBUTION && (known == null || !known.get(next.id()))
                    && seen.add(identity(next.kind(), next.id()))) queue.add(new Start(next.kind(), next.id(), 0));
            }
        }
        return projection.respond(captureId, null, Collections.emptyList(), null, pages);
    }

    private static long identity(Kind kind, int id) {
        return ((long) kind.ordinal() << 32) | id;
    }

    /** Values of a response that depend on the plan's graph rather than on the page. */
    @Desugar
    private record Header(@Nullable Root root, @NotNull Set<CraftingRecipeCapture.Limit> limits, int unplacedCount,
        boolean missingComplete, int missingResourceCount, int unlinkedMissingCount) {}

    /** One response: its pages share the descriptor table and icon mappings. */
    private abstract class Projection {

        private final Map<Integer, Resource> resources = new LinkedHashMap<>();
        final @Nullable IconMappings mappings;

        Projection() {
            IconPack pack = icons ? CoreEngine.getIconPack() : null;
            mappings = pack == null ? null : new IconMappings(pack);
        }

        abstract Mode mode();

        /** Where ROOT starts. */
        abstract Start rootStart();

        /** Whether the node kind does not belong to this mode or the identifier lies outside the capture. */
        abstract boolean isOutside(Kind kind, int id);

        /** Neighborhood of a node whose identifier is known to exist. */
        abstract @NotNull List<Integer> adjacent(Kind kind, int id);

        /** The children from the offset, at most max of them. */
        abstract Page page(Kind kind, int id, List<Integer> rows, int offset, int max);

        abstract Header header();

        final void describe(int id, ResourceDescriptor descriptor, @Nullable BigInteger missing) {
            resources.computeIfAbsent(id, ignored -> new Resource(id, descriptor, mappings, missing));
        }

        final Response respond(String captureId, @Nullable Node node, List<Child> children,
            @Nullable Integer nextOffset, @Nullable List<Page> pages) {
            Header header = header();
            return new Response(
                ApiStatus.OK,
                new Data(
                    State.READY,
                    mode(),
                    captureId,
                    header.root(),
                    node,
                    children,
                    new ArrayList<>(resources.values()),
                    nextOffset,
                    !header.limits()
                        .isEmpty(),
                    header.limits(),
                    header.unplacedCount(),
                    missingOnly,
                    header.missingComplete(),
                    header.missingResourceCount(),
                    header.unlinkedMissingCount(),
                    pages),
                mappings);
        }
    }

    private final class AggregateProjection extends Projection {

        private final CraftingDependencyGraph graph;
        private final CraftingRecipeCapture capture;

        private AggregateProjection(CraftingDependencyGraph graph) {
            this.graph = graph;
            capture = graph.capture();
            CraftingRecipeCapture.Amount result = graph.root();
            if (result != null) resource(result.resourceId());
        }

        @Override
        Mode mode() {
            return Mode.AGGREGATE;
        }

        @Override
        Start rootStart() {
            CraftingRecipeCapture.Amount result = graph.root();
            return result == null ? new Start(Kind.UNPLACED, 0, 0) : new Start(Kind.RESOURCE, result.resourceId(), 0);
        }

        @Override
        boolean isOutside(Kind kind, int id) {
            return switch (kind) {
                case RESOURCE -> id >= capture.resources()
                    .size();
                case RECIPE -> id >= capture.recipes()
                    .size();
                case INGREDIENT -> id >= capture.ingredients()
                    .size();
                case UNPLACED, UNLINKED_MISSING -> false;
                default -> true;
            };
        }

        @Override
        @NotNull
        List<Integer> adjacent(Kind kind, int id) {
            return switch (kind) {
                case RESOURCE -> graph.declaredProducers(id, missingOnly);
                case RECIPE -> graph.ingredients(id, missingOnly);
                case INGREDIENT -> graph.alternatives(id, missingOnly);
                case UNPLACED -> graph.unplacedRecipes(missingOnly);
                case UNLINKED_MISSING -> graph.unlinkedMissingResources();
                default -> throw new IllegalStateException("Node kind outside the aggregate graph");
            };
        }

        void resource(int id) {
            describe(
                id,
                capture.resources()
                    .get(id),
                graph.missingAmount(id));
        }

        @Override
        Page page(Kind kind, int id, List<Integer> rows, int offset, int max) {
            if (kind == Kind.RESOURCE) resource(id);
            int total = rows.size();
            List<Child> children = new ArrayList<>();
            int end = offset + Math.min(max, total - offset);
            for (int index = offset; index < end; index++) {
                int row = rows.get(index);
                Child child = switch (kind) {
                    case RESOURCE -> {
                        CraftingRecipeCapture.Output output = capture.outputs()
                            .get(row);
                        yield new Child(
                            Relation.DECLARED_OUTPUT,
                            node(Kind.RECIPE, output.recipeId()),
                            Long.toString(output.amount()),
                            output.primary());
                    }
                    case RECIPE -> new Child(Relation.INGREDIENT, node(Kind.INGREDIENT, row), null, null);
                    case INGREDIENT -> {
                        CraftingRecipeCapture.Candidate candidate = capture.candidates()
                            .get(row);
                        resource(candidate.resourceId());
                        yield new Child(
                            Relation.DECLARED_ALTERNATIVE,
                            node(Kind.RESOURCE, candidate.resourceId()),
                            Long.toString(candidate.amount()),
                            null);
                    }
                    case UNPLACED -> new Child(Relation.UNPLACED, node(Kind.RECIPE, row), null, null);
                    case UNLINKED_MISSING -> {
                        resource(row);
                        yield new Child(Relation.MISSING_RESOURCE, node(Kind.RESOURCE, row), null, null);
                    }
                    default -> throw new IllegalStateException("Node kind outside the aggregate graph");
                };
                children.add(child);
            }
            return new Page(node(kind, id), offset, children, end < total ? end : null);
        }

        /** Recipes list every declared output and register the output descriptors with this response. */
        private Node node(Kind kind, int id) {
            List<Output> outputs = null;
            if (kind == Kind.RECIPE) {
                outputs = new ArrayList<>();
                for (int row : graph.outputs(id)) {
                    CraftingRecipeCapture.Output output = capture.outputs()
                        .get(row);
                    resource(output.resourceId());
                    outputs.add(new Output(output.resourceId(), Long.toString(output.amount()), output.primary()));
                }
            }
            return new Node(
                kind,
                id,
                kind == Kind.RECIPE ? Long.toString(
                    capture.recipes()
                        .get(id)
                        .crafts())
                    : null,
                kind == Kind.INGREDIENT ? Long.toString(
                    capture.ingredients()
                        .get(id)
                        .multiplier())
                    : null,
                kind == Kind.RECIPE ? capture.recipes()
                    .get(id)
                    .complete() : null,
                kind == Kind.INGREDIENT ? graph.alternatives(id)
                    .size() : null,
                outputs,
                null,
                null,
                null);
        }

        @Override
        Header header() {
            CraftingRecipeCapture.Amount result = graph.root();
            Root root = result == null ? null
                : new Root(result.resourceId(), Long.toString(result.amount()), capture.simulated());
            return new Header(
                root,
                capture.limits(),
                graph.unplacedRecipes(missingOnly)
                    .size(),
                graph.missingComplete(),
                graph.missingResourceCount(),
                graph.unlinkedMissingResources()
                    .size());
        }
    }

    private final class TraceProjection extends Projection {

        private final CraftingTraceGraph graph;
        private final CraftingTraceCapture capture;
        private final int root;

        private TraceProjection(CraftingTraceGraph graph, int root) {
            this.graph = graph;
            this.root = root;
            capture = graph.capture();
            resource(
                capture.requests()
                    .get(root)
                    .resourceId());
        }

        @Override
        Mode mode() {
            return Mode.TRACE;
        }

        @Override
        Start rootStart() {
            return new Start(Kind.REQUEST, root, 0);
        }

        @Override
        boolean isOutside(Kind kind, int id) {
            return switch (kind) {
                case REQUEST -> id >= capture.requests()
                    .size();
                case TASK -> id >= capture.tasks()
                    .size();
                default -> true;
            };
        }

        @Override
        @NotNull
        List<Integer> adjacent(Kind kind, int id) {
            return switch (kind) {
                case REQUEST -> graph.contributions(id, missingOnly);
                case TASK -> graph.taskRequests(id, missingOnly);
                default -> throw new IllegalStateException("Node kind outside the trace graph");
            };
        }

        void resource(int id) {
            describe(
                id,
                capture.resources()
                    .get(id),
                graph.missingAmount(id));
        }

        @Override
        Page page(Kind kind, int id, List<Integer> rows, int offset, int max) {
            int total = rows.size();
            List<Child> children = new ArrayList<>();
            int end = offset + Math.min(max, total - offset);
            for (int index = offset; index < end; index++) {
                int row = rows.get(index);
                if (kind == Kind.REQUEST) {
                    CraftingTraceCapture.Contribution contribution = capture.contributions()
                        .get(row);
                    Integer task = contribution.task();
                    String amount = Long.toString(contribution.amount());
                    children.add(
                        task == null
                            ? new Child(Relation.CONTRIBUTION, contributionNode(row, contribution), amount, null)
                            : new Child(Relation.CRAFT, taskNode(task), amount, null));
                } else {
                    int request = capture.taskRequests()
                        .get(row)
                        .requestId();
                    children.add(
                        new Child(
                            Relation.CHILD_REQUEST,
                            requestNode(request),
                            Long.toString(
                                capture.requests()
                                    .get(request)
                                    .amount()),
                            null));
                }
            }
            Node node = kind == Kind.REQUEST ? requestNode(id) : taskNode(id);
            return new Page(node, offset, children, end < total ? end : null);
        }

        private Node requestNode(int request) {
            int resource = capture.requests()
                .get(request)
                .resourceId();
            resource(resource);
            return new Node(Kind.REQUEST, request, null, null, null, null, null, null, resource, null);
        }

        private Node contributionNode(int row, CraftingTraceCapture.Contribution contribution) {
            int resource = capture.requests()
                .get(contribution.requestId())
                .resourceId();
            resource(resource);
            return new Node(Kind.CONTRIBUTION, row, null, null, null, null, null, null, resource, contribution.kind());
        }

        /** Tasks list their declared outputs and inputs and register those descriptors with this response. */
        private Node taskNode(int task) {
            List<Output> outputs = new ArrayList<>();
            for (int row : graph.outputs(task)) {
                CraftingTraceCapture.Output output = capture.outputs()
                    .get(row);
                resource(output.resourceId());
                outputs.add(new Output(output.resourceId(), Long.toString(output.amount()), output.primary()));
            }
            List<Input> inputs = new ArrayList<>();
            for (int row : graph.inputs(task)) {
                CraftingTraceCapture.Input input = capture.inputs()
                    .get(row);
                resource(input.resourceId());
                inputs.add(new Input(input.resourceId(), Long.toString(input.amount())));
            }
            CraftingTraceCapture.Task value = capture.tasks()
                .get(task);
            return new Node(
                Kind.TASK,
                task,
                Long.toString(value.crafts()),
                null,
                value.complete(),
                null,
                outputs,
                inputs,
                null,
                null);
        }

        @Override
        Header header() {
            CraftingTraceCapture.Request request = capture.requests()
                .get(root);
            return new Header(
                new Root(request.resourceId(), Long.toString(request.amount()), capture.simulated()),
                capture.limits(),
                0,
                graph.missingComplete(),
                graph.missingResourceCount(),
                0);
        }
    }
}
