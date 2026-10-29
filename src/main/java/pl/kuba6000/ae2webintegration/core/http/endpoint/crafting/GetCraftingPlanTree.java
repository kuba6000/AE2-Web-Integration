package pl.kuba6000.ae2webintegration.core.http.endpoint.crafting;

import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
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
 * Browse declared dependencies of a retained calculated plan. Capture is lazy and shared by viewers.
 * Links describe recipe declarations, never selected substitutes or per-consumer material allocation.
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

    public enum Kind {
        ROOT,
        RESOURCE,
        RECIPE,
        INGREDIENT,
        UNPLACED,
        UNLINKED_MISSING
    }

    public enum State {
        CALCULATING,
        PREPARING,
        UNAVAILABLE,
        READY
    }

    public enum Relation {
        DECLARED_OUTPUT,
        RECIPE_OUTPUT,
        INGREDIENT,
        DECLARED_ALTERNATIVE,
        MISSING_RESOURCE,
        UNPLACED
    }

    @PathParam("planId")
    int planId;
    /** ROOT, RESOURCE, RECIPE, INGREDIENT, UNPLACED or UNLINKED_MISSING. ROOT resolves the calculated output. */
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
    private @Nullable CraftingPlanRegistry.Entry entry;
    private @Nullable CraftingPlanRegistry.TreePreparation preparation;

    @Desugar
    public record Root(int resourceId, @NotNull String quantity, boolean simulated) {}

    @Desugar
    public record Node(@NotNull Kind kind, int id, @Nullable String crafts, @Nullable String multiplier,
        @Nullable Boolean complete) {}

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

    @Desugar
    public record Data(@NotNull State state, @Nullable String captureId, @Nullable Root root, @Nullable Node node,
        @NotNull List<Child> children, @NotNull List<Resource> resources, @Nullable Integer nextOffset, boolean partial,
        @NotNull Set<CraftingRecipeCapture.Limit> limits, int unplacedCount, boolean missingOnly,
        boolean missingComplete, int missingResourceCount, int unlinkedMissingCount) {}

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
            || limit < 1
            || limit > MAX_PAGE_CHILDREN
            || ((kind == Kind.UNPLACED || kind == Kind.UNLINKED_MISSING) && nodeId != 0)
            || (expectedCaptureId != null && expectedCaptureId.length() > 64)) {
            deny(ApiStatus.BAD_PARAM);
            return false;
        }
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
                CraftingDependencyGraph graph = preparation.graph();
                body = graph == null ? state(State.UNAVAILABLE) : page(graph, preparation.id());
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
                0),
            null);
    }

    private @Nullable Response page(CraftingDependencyGraph graph, String captureId) {
        CraftingRecipeCapture capture = graph.capture();
        CraftingRecipeCapture.Amount result = graph.root();
        Kind resolvedKind = kind == Kind.ROOT ? result == null ? Kind.UNPLACED : Kind.RESOURCE : kind;
        int resolvedId = kind == Kind.ROOT ? result == null ? 0 : result.resourceId() : nodeId;
        if ((resolvedKind == Kind.RESOURCE && resolvedId >= capture.resources()
            .size()) || (resolvedKind == Kind.RECIPE && resolvedId
                >= capture.recipes()
                    .size())
            || (resolvedKind == Kind.INGREDIENT && resolvedId >= capture.ingredients()
                .size()))
            return null;
        List<Integer> adjacent = switch (resolvedKind) {
            case RESOURCE -> graph.declaredProducers(resolvedId, missingOnly);
            case RECIPE -> graph.ingredients(resolvedId, missingOnly);
            case INGREDIENT -> graph.alternatives(resolvedId, missingOnly);
            case UNPLACED -> graph.unplacedRecipes(missingOnly);
            case UNLINKED_MISSING -> graph.unlinkedMissingResources();
            default -> throw new IllegalStateException("Unresolved root");
        };
        List<Integer> declaredOutputs = resolvedKind == Kind.RECIPE && !missingOnly ? graph.outputs(resolvedId)
            : Collections.emptyList();
        int total = declaredOutputs.size() + adjacent.size();
        if (offset > total) return null;
        IconPack pack = icons ? CoreEngine.getIconPack() : null;
        IconMappings mappings = pack == null ? null : new IconMappings(pack);
        Map<Integer, Resource> resources = new LinkedHashMap<>();
        if (result != null) resource(resources, graph, result.resourceId(), mappings);
        if (resolvedKind == Kind.RESOURCE) resource(resources, graph, resolvedId, mappings);
        List<Child> children = new ArrayList<>();
        int end = offset + Math.min(limit, total - offset);
        for (int index = offset; index < end; index++) {
            if (index < declaredOutputs.size()) {
                CraftingRecipeCapture.Output output = capture.outputs()
                    .get(declaredOutputs.get(index));
                resource(resources, graph, output.resourceId(), mappings);
                children.add(
                    new Child(
                        Relation.RECIPE_OUTPUT,
                        node(capture, Kind.RESOURCE, output.resourceId()),
                        Long.toString(output.amount()),
                        output.primary()));
                continue;
            }
            int row = adjacent.get(index - declaredOutputs.size());
            Child child = switch (resolvedKind) {
                case RESOURCE -> {
                    CraftingRecipeCapture.Output output = capture.outputs()
                        .get(row);
                    yield new Child(
                        Relation.DECLARED_OUTPUT,
                        node(capture, Kind.RECIPE, output.recipeId()),
                        Long.toString(output.amount()),
                        output.primary());
                }
                case RECIPE -> new Child(Relation.INGREDIENT, node(capture, Kind.INGREDIENT, row), null, null);
                case INGREDIENT -> {
                    CraftingRecipeCapture.Candidate candidate = capture.candidates()
                        .get(row);
                    resource(resources, graph, candidate.resourceId(), mappings);
                    yield new Child(
                        Relation.DECLARED_ALTERNATIVE,
                        node(capture, Kind.RESOURCE, candidate.resourceId()),
                        Long.toString(candidate.amount()),
                        null);
                }
                case UNPLACED -> new Child(Relation.UNPLACED, node(capture, Kind.RECIPE, row), null, null);
                case UNLINKED_MISSING -> {
                    resource(resources, graph, row, mappings);
                    yield new Child(Relation.MISSING_RESOURCE, node(capture, Kind.RESOURCE, row), null, null);
                }
                default -> throw new IllegalStateException("Unresolved root");
            };
            children.add(child);
        }
        Root root = result == null ? null
            : new Root(result.resourceId(), Long.toString(result.amount()), capture.simulated());
        return new Response(
            ApiStatus.OK,
            new Data(
                State.READY,
                captureId,
                root,
                node(capture, resolvedKind, resolvedId),
                children,
                new ArrayList<>(resources.values()),
                end < total ? end : null,
                !capture.limits()
                    .isEmpty(),
                capture.limits(),
                graph.unplacedRecipes(missingOnly)
                    .size(),
                missingOnly,
                graph.missingComplete(),
                graph.missingResourceCount(),
                graph.unlinkedMissingResources()
                    .size()),
            mappings);
    }

    private static Node node(CraftingRecipeCapture capture, Kind kind, int id) {
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
                .complete() : null);
    }

    private static void resource(Map<Integer, Resource> resources, CraftingDependencyGraph graph, int id,
        @Nullable IconMappings mappings) {
        resources.computeIfAbsent(
            id,
            ignored -> new Resource(
                id,
                graph.capture()
                    .resources()
                    .get(id),
                mappings,
                graph.missingAmount(id)));
    }
}
