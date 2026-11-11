package pl.kuba6000.ae2webintegration.core.http.endpoint.crafting;

import static java.net.HttpURLConnection.HTTP_OK;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingDependencyGraph;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.Limit;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.ContributionKind;
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
 * Read the complete detached dependency graph of a retained crafting plan. Capture is lazy and shared
 * by viewers. AGGREGATE may assign captured totals to an ordered visualization, or show recipe
 * declarations when allocation is uncertain. TRACE reflects the native job's requests and contributions.
 * The aggregate order is a display convention, not a native execution trace.
 * Node and recipe identifiers are local to the capture.
 * Quantities are decimal strings. Recipe quantities are per craft; child quantities belong to the link.
 * Missing flags identify paths to missing resources in the selected representation.
 *
 * @pathParam gridKey Persistent network identifier.
 * @pathParam planId Retained crafting plan identifier.
 * @response 200 {@link Response} Preparation state, complete document or TOO_LARGE.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid capture identity.
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
    private static final int MAX_CAPTURE_ENTRIES = 500_000;
    private static final int MAX_DESCRIPTION_CHARACTERS = 2 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    public enum Kind {
        RESOURCE,
        RECIPE,
        INGREDIENT,
        UNPLACED,
        UNLINKED_MISSING,
        REQUEST,
        TASK,
        CONTRIBUTION
    }

    public enum Mode {
        TRACE,
        AGGREGATE
    }

    public enum State {
        CALCULATING,
        PREPARING,
        UNAVAILABLE,
        READY,
        TOO_LARGE
    }

    public enum Relation {
        DECLARED_OUTPUT,
        INGREDIENT,
        DECLARED_ALTERNATIVE,
        MISSING_RESOURCE,
        UNPLACED,
        CONTRIBUTION,
        CRAFT,
        CHILD_REQUEST
    }

    @PathParam("planId")
    int planId;
    /** Optional expected capture identity; stale identities return UPDATE_CHANGED. */
    @QueryParam("captureId")
    @Nullable
    String expectedCaptureId;
    /** Include available response-local atlas references. */
    @QueryParam("icons")
    boolean icons;
    private @Nullable CraftingPlanRegistry.Entry entry;
    private @Nullable CraftingPlanRegistry.TreePreparation preparation;

    @Desugar
    public record Root(int resourceId, @NotNull String quantity, boolean simulated) {}

    /** One candidate in a per-craft input declaration. */
    @Desugar
    public record Candidate(int resourceId, @Nullable String quantity) {}

    /** A per-craft ingredient group and its declared candidates. */
    @Desugar
    public record InputGroup(@Nullable String multiplier, @NotNull List<Candidate> candidates) {}

    /** A per-craft output; primary is null when the native plan does not retain the designation. */
    @Desugar
    public record Output(int resourceId, @NotNull String quantity, @Nullable Boolean primary) {}

    /** A declaration shared by occurrences with equal captured inputs and outputs. */
    @Desugar
    public record Recipe(int id, @NotNull List<InputGroup> inputs, @NotNull List<Output> outputs) {}

    /** A connection to a capture-local node; missing marks relevance to a known missing path. */
    @Desugar
    public record Child(int nodeId, @NotNull Relation relation, @Nullable String quantity, @Nullable Boolean primary,
        boolean missing) {}

    /** An occurrence, with fields populated according to its kind and no nested node copies. */
    @Desugar
    public record Node(int id, @NotNull Kind kind, @Nullable Integer resourceId, @Nullable Integer recipeId,
        @Nullable String crafts, @Nullable String multiplier, @Nullable Boolean complete, @Nullable Integer candidates,
        @Nullable ContributionKind contribution, @NotNull List<Child> children) {}

    /** A capture-local resource descriptor with response-local icon references. */
    public static final class Resource extends ResourceDescription {

        public final int id;
        /** Null means absence or the exact accumulated amount is unknown. */
        public final @Nullable String missingAmount;

        /** Known positive shortage, independent of whether its exact amount fits in long. */
        public final boolean missing;

        Resource(int id, ResourceDescriptor descriptor, @Nullable IconMappings mappings, @Nullable Long amount,
            boolean missing) {
            super(descriptor, mappings);
            this.id = id;
            missingAmount = amount == null ? null : amount.toString();
            this.missing = missing;
        }
    }

    @Desugar
    public record Data(@NotNull State state, @Nullable Mode mode, @Nullable String captureId, @Nullable Root root,
        @NotNull List<Integer> roots, @NotNull List<Resource> resources, @NotNull List<Recipe> recipes,
        @NotNull List<Node> nodes, boolean partial, @NotNull Set<Limit> limits, boolean missingComplete,
        int missingResourceCount) {}

    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull Data data, @Nullable IconMappings icons)
        implements IResponseBody {}

    @Override
    public boolean init(AE2Controller.RequestContext context) {
        if (!super.init(context)) return false;
        if (expectedCaptureId != null && expectedCaptureId.length() > 64) {
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
            done();
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
        ApiResponse completed = getResponse();
        if (completed.httpStatus() != HTTP_OK || entry == null) return completed;
        ApiStatus failure = accessFailure();
        if (failure != null) return ApiResponse.error(failure);
        try {
            if (expectedCaptureId != null && (preparation == null || !expectedCaptureId.equals(preparation.id()))) {
                return ApiResponse.error(ApiStatus.UPDATE_CHANGED);
            }
            Response body;
            if (preparation == null) body = state(State.CALCULATING);
            else if (!preparation.isCaptured()) body = state(State.PREPARING);
            else {
                CraftingTraceGraph trace = preparation.traceGraph();
                CraftingDependencyGraph aggregate = trace == null ? preparation.graph() : null;
                if (trace == null && aggregate == null) body = state(State.UNAVAILABLE);
                else {
                    IconPack pack = icons ? CoreEngine.getIconPack() : null;
                    body = trace == null ? CraftingTreeDocument.from(aggregate, pack, preparation.id())
                        : CraftingTreeDocument.from(
                            trace,
                            pack,
                            preparation.id(),
                            preparation.isNativeTrace() ? Mode.TRACE : Mode.AGGREGATE);
                }
            }
            ApiResponse response;
            try {
                response = ApiResponse.bounded(HTTP_OK, body, MAX_RESPONSE_BYTES);
            } catch (ApiResponse.SizeLimitExceeded tooLarge) {
                response = ApiResponse.bounded(HTTP_OK, state(State.TOO_LARGE), MAX_RESPONSE_BYTES);
            }
            failure = accessFailure();
            return failure == null ? response : ApiResponse.error(failure);
        } catch (RuntimeException unexpected) {
            LOG.error("Failed to prepare crafting dependency document", unexpected);
            return ApiResponse.error(ApiStatus.INTERNAL_ERROR);
        }
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
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                false,
                Collections.emptySet(),
                false,
                0),
            null);
    }

}
