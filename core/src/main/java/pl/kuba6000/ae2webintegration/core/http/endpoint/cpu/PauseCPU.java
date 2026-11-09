package pl.kuba6000.ae2webintegration.core.http.endpoint.cpu;

import java.net.HttpURLConnection;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.cpu.CpuLiveCoordinator;
import pl.kuba6000.ae2webintegration.core.cpu.CpuReader;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPausableCraftingCPU;

/**
 * Pause or resume crafting.
 *
 * <p>
 * Pausing prevents the CPU from scheduling new work. Work already sent to machines can still finish.
 *
 * @pathParam gridKey Persistent network identifier.
 * @pathParam cpuKey Crafting CPU identifier.
 * @response 200 {@link Response} Pause state updated.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters or body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied. CSRF_REJECTED: cookie or
 *           configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: network not found. CPU_NOT_FOUND: no CPU has this key.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 409 {@link ErrorResponse} CPU_NOT_BUSY: the CPU has no current work. CPU_PAUSE_UNSUPPORTED: the CPU does
 *           not support pausing.
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
 * @responseExample 409 {"status":"CPU_NOT_BUSY","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/cpus/{cpuKey}/pause")
public final class PauseCPU extends ISyncedRequest {

    /**
     * @param status {@code OK} for a successful request
     * @param data   always {@code null} after the requested pause state has been applied
     * @example status OK
     * @example data null
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @Nullable Void data) implements IResponseBody {}

    /**
     * Desired scheduling state for the current job.
     *
     * @param paused true to suspend scheduling, false to resume it
     * @example paused true
     */
    @Desugar
    public record Input(boolean paused) {}

    @Body
    @NotNull
    Input input;

    @PathParam("cpuKey")
    @NotNull
    StableKey cpuId;

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        Map<StableKey, ICraftingCPUCluster> cpus = CpuReader.list(grid.web$getCraftingGrid());
        ICraftingCPUCluster cluster = cpus.get(cpuId);
        if (cluster == null) {
            deny(ApiStatus.CPU_NOT_FOUND);
            return;
        }
        if (!cluster.web$isBusy()) {
            deny(ApiStatus.CPU_NOT_BUSY);
            return;
        }
        if (!(cluster instanceof IPausableCraftingCPU pausable)) {
            deny(ApiStatus.CPU_PAUSE_UNSUPPORTED);
            return;
        }
        pausable.web$setPaused(input.paused());
        CpuLiveCoordinator live = CoreEngine.getCpuLive();
        if (live != null) live.metadataChanged(cluster);
        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, null));
    }
}
