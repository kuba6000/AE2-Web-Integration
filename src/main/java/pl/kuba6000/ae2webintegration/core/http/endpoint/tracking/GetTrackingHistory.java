package pl.kuba6000.ae2webintegration.core.http.endpoint.tracking;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceOutput;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

/**
 * Lists crafting history entries.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @response 200 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: the grid does not exist.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: the request could not be completed because of an unexpected
 *           failure.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"NO_PERMISSIONS","data":null}
 * @responseExample 404 {"status":"GRID_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/crafting-history")
public final class GetTrackingHistory extends IAsyncRequest {

    /** Include product icon references and atlas metadata; omitted means false. */
    @QueryParam("icons")
    private boolean icons;

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   history entries ordered by completion time, newest first; empty when no history has been recorded
     * @param icons  atlas metadata for product references; null when not requested or no pack is available
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<HistoryEntry> data, @Nullable IconMappings icons) {}

    /**
     * One completed or cancelled crafting job.
     *
     * @param timeStarted  crafting start time in Unix epoch milliseconds
     * @param timeDone     completion time in Unix epoch milliseconds
     * @param wasCancelled whether the crafting work was cancelled
     * @param finalOutput  detached snapshot of the final crafting output
     * @param id           runtime history entry identifier
     * @example timeStarted 1700000000000
     * @example timeDone 1700000010000
     * @example wasCancelled false
     * @example id 12
     */
    @Desugar
    public record HistoryEntry(long timeStarted, long timeDone, boolean wasCancelled,
        @NotNull ResourceOutput finalOutput, int id) {}

    @Override
    public void handle() {
        IconPack pack = icons ? CoreEngine.getIconPack() : null;
        IconMappings mappings = pack == null ? null : new IconMappings(pack);
        if (grid == null) {
            // Nothing has ever been tracked on this grid; an empty history is the honest answer.
            respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, new ArrayList<HistoryEntry>(), mappings));
            return;
        }
        ArrayList<HistoryEntry> jobs = new ArrayList<>(grid.trackingInfo.trackingInfos.size());

        for (Map.Entry<Integer, AE2JobTracker.JobTrackingInfo> integerJobTrackingInfoEntry : grid.trackingInfo.trackingInfos
            .entrySet()) {
            AE2JobTracker.JobTrackingInfo info = integerJobTrackingInfoEntry.getValue();
            jobs.add(
                new HistoryEntry(
                    info.timeStarted,
                    info.timeDone,
                    info.wasCancelled,
                    new ResourceOutput(info.finalOutput, mappings),
                    integerJobTrackingInfoEntry.getKey()));
        }

        jobs.sort((i1, i2) -> Long.compare(i2.timeDone(), i1.timeDone()));

        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, jobs, mappings));
    }

}
