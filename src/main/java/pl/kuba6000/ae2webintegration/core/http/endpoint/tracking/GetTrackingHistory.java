package pl.kuba6000.ae2webintegration.core.http.endpoint.tracking;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.api.JSON_Stack;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
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

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   history entries ordered by completion time, newest first; empty when no history has been recorded
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<HistoryEntry> data) {}

    @SuppressWarnings("unused") // Gson reads the fields reflectively.
    public static class HistoryEntry {

        /**
         * Crafting start time in Unix epoch milliseconds.
         *
         * @example 1700000000000
         */
        public long timeStarted;
        /**
         * Completion time in Unix epoch milliseconds.
         *
         * @example 1700000010000
         */
        public long timeDone;
        /**
         * Whether the crafting work was cancelled.
         *
         * @example false
         */
        public boolean wasCancelled;
        /** Detached snapshot of the final crafting output. */
        public final @NotNull JSON_Stack finalOutput;
        /**
         * Runtime history entry identifier.
         *
         * @example 12
         */
        public int id;

        private HistoryEntry(@NotNull JSON_Stack finalOutput) {
            this.finalOutput = finalOutput;
        }
    }

    @Override
    public void handle() {
        if (grid == null) {
            // Nothing has ever been tracked on this grid; an empty history is the honest answer.
            respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, new ArrayList<HistoryEntry>()));
            return;
        }
        ArrayList<HistoryEntry> jobs = new ArrayList<>(grid.trackingInfo.trackingInfos.size());

        for (Map.Entry<Integer, AE2JobTracker.JobTrackingInfo> integerJobTrackingInfoEntry : grid.trackingInfo.trackingInfos
            .entrySet()) {
            HistoryEntry element = new HistoryEntry(integerJobTrackingInfoEntry.getValue().finalOutput);
            element.id = integerJobTrackingInfoEntry.getKey();
            element.timeStarted = integerJobTrackingInfoEntry.getValue().timeStarted;
            element.timeDone = integerJobTrackingInfoEntry.getValue().timeDone;
            element.wasCancelled = integerJobTrackingInfoEntry.getValue().wasCancelled;
            jobs.add(element);
        }

        jobs.sort((i1, i2) -> Long.compare(i2.timeDone, i1.timeDone));

        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, jobs));
    }

}
