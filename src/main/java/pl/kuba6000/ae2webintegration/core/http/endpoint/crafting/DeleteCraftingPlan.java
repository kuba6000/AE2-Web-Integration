package pl.kuba6000.ae2webintegration.core.http.endpoint.crafting;

import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.grid.CraftingPlanRegistry;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;

/**
 * Delete crafting plan.
 *
 * <p>
 * Cancels any pending calculation and removes the plan. To stop an active job, cancel its CPU instead.
 *
 * @pathParam gridKey Persistent network identifier.
 * @pathParam planId Plan identifier returned as jobId when creating the plan.
 * @response 200 {@link Response} Plan removed.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters or body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied. CSRF_REJECTED: cookie or
 *           configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND or INVALID_ID: network or plan not found.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} SERVER_BUSY, SERVER_STOPPING or TIMEOUT: server unavailable or request timed out.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"NO_PERMISSIONS","data":null}
 * @responseExample 404 {"status":"GRID_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.DELETE, path = "/api/grids/{gridKey}/crafting-plans/{planId}")
public final class DeleteCraftingPlan extends ISyncedRequest {

    /**
     * @param status {@code OK} for a successful request
     * @param data   always {@code null} after the plan is cancelled and removed
     * @example status OK
     * @example data null
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @Nullable Void data) implements IResponseBody {}

    @PathParam("planId")
    int jobId;

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        CraftingPlanRegistry.Entry entry = gridData.getJob(jobId);
        if (entry == null) {
            deny(ApiStatus.INVALID_ID);
            return;
        }
        gridData.cancelJob(jobId);
        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, null));
    }
}
