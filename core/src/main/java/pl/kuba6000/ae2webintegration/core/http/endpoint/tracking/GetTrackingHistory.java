package pl.kuba6000.ae2webintegration.core.http.endpoint.tracking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceStack;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.tracking.crafting.CraftingHistoryService;
import pl.kuba6000.ae2webintegration.core.tracking.crafting.SqliteCraftingHistoryStore;

/**
 * List crafting history.
 *
 * <p>
 * Returns completed and cancelled jobs, newest first. Pass {@code nextBefore} as {@code before}
 * to request the next page.
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 200 {@link Response} Crafting history page.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters or body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: network not found.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: history is unavailable.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"NO_PERMISSIONS","data":null}
 * @responseExample 404 {"status":"GRID_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_STOPPING","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/crafting-history")
public final class GetTrackingHistory extends IAsyncRequest {

    /** Include product icons and atlas metadata; defaults to false. */
    @QueryParam("icons")
    boolean icons;

    /** Maximum number of entries, from 1 to 100; defaults to 50. */
    @QueryParam("limit")
    int limit = 50;

    /** Pagination cursor: use nextBefore from the preceding response; omit for the newest entries. */
    @QueryParam("before")
    String before;

    /**
     * @param status     {@code OK} for a successful request
     * @param data       completed and cancelled jobs, newest first; empty when no entries match
     * @param icons      atlas metadata for product references; null when not requested or no pack is available
     * @param nextBefore exclusive decimal ID cursor for the next page, or null when this is the last page
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<HistoryEntry> data, @Nullable IconMappings icons,
        @Nullable String nextBefore) implements IResponseBody {}

    /**
     * One completed or cancelled crafting job.
     *
     * @param timeStarted  crafting start time in Unix epoch milliseconds
     * @param timeDone     completion time in Unix epoch milliseconds
     * @param wasCancelled whether the crafting work was cancelled
     * @param finalOutput  requested output of the job
     * @param requesters   player, automation and web requesters, in submission order; empty when unavailable
     * @param id           history entry ID, as a decimal string
     * @example timeStarted 1700000000000
     * @example timeDone 1700000010000
     * @example wasCancelled false
     * @example id "12"
     */
    @Desugar
    public record HistoryEntry(long timeStarted, long timeDone, boolean wasCancelled,
        @NotNull ResourceStack finalOutput, @NotNull String id, @NotNull List<CraftingRequester> requesters) {}

    @Override
    public void handle() {
        Long cursor = null;
        if (before != null) {
            try {
                cursor = Long.parseLong(before);
            } catch (NumberFormatException invalid) {
                deny(ApiStatus.BAD_PARAM);
                return;
            }
            if (cursor < 1 || !Long.toString(cursor)
                .equals(before)) {
                deny(ApiStatus.BAD_PARAM);
                return;
            }
        }
        if (limit < 1 || limit > 100) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        CraftingHistoryService service = CoreEngine.getCraftingHistory();
        if (service == null) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        try {
            SqliteCraftingHistoryStore.Page page = service.list(gridKey, cursor, limit);
            IconPack pack = icons ? CoreEngine.getIconPack() : null;
            IconMappings mappings = pack == null ? null : new IconMappings(pack);
            ArrayList<HistoryEntry> jobs = new ArrayList<>();
            for (SqliteCraftingHistoryStore.Summary entry : page.entries()) {
                jobs.add(
                    new HistoryEntry(
                        entry.timeStarted(),
                        entry.timeDone(),
                        entry.wasCancelled(),
                        new ResourceStack(entry.finalOutput(), mappings),
                        Long.toString(entry.id()),
                        entry.requesters()));
            }
            respondDetached(
                HttpURLConnection.HTTP_OK,
                new Response(
                    ApiStatus.OK,
                    jobs,
                    mappings,
                    page.nextBefore() == null ? null : Long.toString(page.nextBefore())));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
