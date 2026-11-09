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
import pl.kuba6000.ae2webintegration.core.cpu.CpuReader;
import pl.kuba6000.ae2webintegration.core.grid.CraftingPlanRegistry;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.CraftingRequesterContext;

/**
 * Submit crafting plan.
 *
 * <p>
 * Starts crafting from a completed plan. Omit {@code cpuKey} to let AE2 choose a CPU.
 * A successful submission removes the plan.
 *
 * @pathParam gridKey Persistent network identifier.
 * @pathParam planId Plan identifier returned as jobId when creating the plan.
 * @response 200 {@link Response} Crafting started; the plan has been removed.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters or body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied. CSRF_REJECTED: cookie or
 *           configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND, INVALID_ID or CPU_NOT_FOUND: the selected resource does not
 *           exist.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 409 {@link FailureResponse} FAIL: crafting could not start; JOB_NOT_DONE: calculation is pending.
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
     * @param status {@code OK} for a successful request
     * @param data   always {@code null}
     * @example status OK
     * @example data null
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @Nullable Void data) implements IResponseBody {}

    /**
     * Crafting submission failure.
     * 
     * @param status {@code FAIL} when submission is rejected, or {@code JOB_NOT_DONE} while calculation is
     *               pending
     * @param data   failure reason, which varies by Minecraft version; null while calculation is pending
     * @example status FAIL
     * @example data CPU is busy with another job.
     */
    @Desugar
    public record FailureResponse(@NotNull ApiStatus status, @Nullable String data) implements IResponseBody {}

    /**
     * Optional CPU selection for submission.
     *
     * @param cpuKey stable CPU key; omission lets the crafting service choose a CPU
     * @example cpuKey AQEBAQEBAQEBAQEBAQEBAQ
     */
    @Desugar
    public record Input(@OptionalInput @Nullable StableKey cpuKey) {}

    @Body
    @NotNull
    Input input;

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
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        Future<IAECraftingJob> job = entry.plan;
        if (job.isDone()) {
            try {
                IAECraftingJob craftingJob = job.get();
                ICraftingCPUCluster target = null;
                if (input.cpuKey() != null) {
                    Map<StableKey, ICraftingCPUCluster> cpus = CpuReader.list(craftingGrid);
                    target = cpus.get(input.cpuKey());
                    if (target == null) {
                        deny(ApiStatus.CPU_NOT_FOUND);
                        return;
                    }
                }
                String error;
                try (CraftingRequesterContext ignored = CraftingRequesterContext.open(
                    context.getPrincipal()
                        .getPlayerIdentity())) {
                    error = craftingGrid.web$submitJob(craftingJob, target, true, grid);
                }
                if (error != null) {
                    respond(HttpURLConnection.HTTP_CONFLICT, new FailureResponse(ApiStatus.FAIL, error));
                } else {
                    gridData.removeJob(this.jobId);
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
