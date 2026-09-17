package pl.kuba6000.ae2webintegration.core.http.endpoint.crafting;

import static pl.kuba6000.ae2webintegration.core.AE2Controller.itemIdentities;

import java.net.HttpURLConnection;
import java.util.concurrent.Future;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

/**
 * Starts calculating a crafting plan.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @response 202 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields. INVALID_QUANTITY: quantity must be positive.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid. CSRF_REJECTED: cookie or
 *           configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND, ITEM_IDENTITY_UNKNOWN or ITEM_NOT_FOUND: the grid or
 *           craftable resource does not exist.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 409 {@link ErrorResponse} ALL_CPU_BUSY or AMBIGUOUS_ITEM_KEY: the request conflicts with current state.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: the request could not be completed because of an unexpected
 *           failure.
 * @response 503 {@link ErrorResponse} SERVER_BUSY, SERVER_STOPPING or TIMEOUT: the game-thread request could not
 *           complete.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"NO_PERMISSIONS","data":null}
 * @responseExample 404 {"status":"GRID_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 409 {"status":"ALL_CPU_BUSY","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/crafting-plans")
public final class CreateCraftingPlan extends ISyncedRequest {

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   identifier of the accepted calculation; use it to poll, submit or delete the plan
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull PlanCreated data) {}

    /**
     * Accepted crafting calculation.
     * 
     * @param jobID identifier local to this grid and server runtime, used to poll, submit or delete the plan
     * @example jobID 7
     */
    @Desugar
    public record PlanCreated(int jobID) {}

    /** Parameters for a new crafting plan. */
    public static final class Input {

        /**
         * Stable resource identity returned by the item listing.
         *
         * @example AAAAAAAAAAAAAAAAAAAAAA
         */
        @SuppressWarnings("NotNullFieldNotInitialized") // Validated and assigned by JSON input binding.
        public @NotNull StableKey itemKey;
        /**
         * Positive number of resource units to craft.
         *
         * @example 64
         */
        public long quantity;
    }

    @Body
    @SuppressWarnings("NotNullFieldNotInitialized") // Bound before the request is handled.
    private @NotNull Input input;

    @Override
    public boolean init(RequestContext context) {
        if (!super.init(context)) return false;
        if (input.quantity <= 0) {
            deny(ApiStatus.INVALID_QUANTITY);
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
        IAEKey itemKey;
        try {
            itemKey = itemIdentities.resolve(input.itemKey);
        } catch (ItemIdentityRegistry.Ambiguous e) {
            deny(ApiStatus.AMBIGUOUS_ITEM_KEY);
            return;
        }
        if (itemKey == null) {
            deny(ApiStatus.ITEM_IDENTITY_UNKNOWN);
            return;
        }
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        if (!craftingGrid.web$isCurrentlyCraftable(itemKey)) {
            deny(ApiStatus.ITEM_NOT_FOUND);
            return;
        }
        boolean allBusy = true;
        for (ICraftingCPUCluster cpu : craftingGrid.web$getCPUs()) {
            if (!cpu.web$isBusy()) {
                allBusy = false;
                break;
            }
        }
        if (!allBusy) {
            Future<IAECraftingJob> job = craftingGrid.web$beginCraftingJob(grid, itemKey, input.quantity);

            int jobID = gridData.addJob(job);
            respond(HttpURLConnection.HTTP_ACCEPTED, new Response(ApiStatus.OK, new PlanCreated(jobID)));
        } else {
            deny(ApiStatus.ALL_CPU_BUSY);
        }
    }

}
