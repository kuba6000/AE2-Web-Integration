package pl.kuba6000.ae2webintegration.core.http.endpoint.cpu;

import java.net.HttpURLConnection;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;

/**
 * Cancels the current work of a crafting CPU.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @pathParam cpuKey Stable crafting CPU identifier.
 * @response 200 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid. CSRF_REJECTED: cookie or
 *           configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: the grid does not exist. CPU_NOT_FOUND: no CPU has this key.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 409 {@link ErrorResponse} CPU_NOT_BUSY: the CPU has no current work.
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
 * @responseExample 409 {"status":"CPU_NOT_BUSY","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/cpus/{cpuKey}/cancel")
public final class CancelCPU extends ISyncedRequest {

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   always {@code null} after the current CPU job is cancelled
     * @example status OK
     * @example data null
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @Nullable Void data) {}

    @PathParam("cpuKey")
    @SuppressWarnings("NotNullFieldNotInitialized") // Bound before the request is submitted.
    private @NotNull StableKey cpuId;

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        Map<StableKey, ICraftingCPUCluster> cpus = GetCPUList.getCPUList(grid.web$getCraftingGrid());
        ICraftingCPUCluster cluster = cpus.get(cpuId);
        if (cluster == null) {
            deny(ApiStatus.CPU_NOT_FOUND);
            return;
        }
        if (cluster.web$isBusy()) {
            cluster.web$cancel();
            respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, null));
            return;
        }
        deny(ApiStatus.CPU_NOT_BUSY);
    }
}
