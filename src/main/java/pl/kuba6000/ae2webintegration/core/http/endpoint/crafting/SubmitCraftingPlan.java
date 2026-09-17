package pl.kuba6000.ae2webintegration.core.http.endpoint.crafting;

import java.net.HttpURLConnection;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.http.endpoint.cpu.GetCPUList;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

/**
 * Submits a completed crafting plan to a crafting CPU.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @pathParam planId Runtime crafting-plan identifier.
 * @response 200 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid. CSRF_REJECTED: cookie or
 *           configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND, INVALID_ID or CPU_NOT_FOUND: the selected resource does not
 *           exist.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 409 {@link FailureResponse} FAIL: native submission rejected; JOB_NOT_DONE: calculation is pending.
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
 * @responseExample 409 {"status":"JOB_NOT_DONE","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/crafting-plans/{planId}/submit")
public final class SubmitCraftingPlan extends ISyncedRequest {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   always {@code null} after the plan is successfully submitted and removed from pending plans
     * @example status OK
     * @example data null
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @Nullable Void data) {}

    /**
     * Submission failure; a native crafting service may provide the reason.
     * 
     * @param status {@code FAIL} when native submission is rejected, or {@code JOB_NOT_DONE} while calculation is
     *               pending
     * @param data   native submission failure text, which varies by platform and failure; null if calculation has not
     *               finished
     * @example status FAIL
     * @example data CPU is busy with another job.
     */
    @Desugar
    public record FailureResponse(@NotNull ApiStatus status, @Nullable String data) {}

    /** Optional CPU selection for submission. */
    public static final class Input {

        /**
         * Stable CPU key; omission lets the crafting service choose a CPU.
         *
         * @example AQEBAQEBAQEBAQEBAQEBAQ
         */
        @OptionalInput
        public @Nullable StableKey cpuKey;
    }

    @Body
    @SuppressWarnings("NotNullFieldNotInitialized") // Bound before the request is handled.
    private @NotNull Input input;

    @PathParam("planId")
    private int jobID;

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        Future<IAECraftingJob> job = gridData.getJob(jobID);
        if (job == null) {
            deny(ApiStatus.INVALID_ID);
            return;
        }
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        if (job.isDone()) {
            try {
                IAECraftingJob craftingJob = job.get();
                ICraftingCPUCluster target = null;
                if (input.cpuKey != null) {
                    Map<StableKey, ICraftingCPUCluster> cpus = GetCPUList.getCPUList(craftingGrid);
                    target = cpus.get(input.cpuKey);
                    if (target == null) {
                        deny(ApiStatus.CPU_NOT_FOUND);
                        return;
                    }
                }
                String error = craftingGrid.web$submitJob(craftingJob, target, true, grid);
                if (error != null) {
                    respond(HttpURLConnection.HTTP_CONFLICT, new FailureResponse(ApiStatus.FAIL, error));
                } else {
                    gridData.removeJob(this.jobID);
                    respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, null));
                }
            } catch (InterruptedException | ExecutionException e) {
                LOG.error("Failed to submit crafting job", e);
                deny(ApiStatus.INTERNAL_ERROR);
            }
        } else {
            respond(HttpURLConnection.HTTP_CONFLICT, new FailureResponse(ApiStatus.JOB_NOT_DONE, null));
        }
    }
}
