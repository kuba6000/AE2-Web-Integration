package pl.kuba6000.ae2webintegration.core.http.endpoint.tracking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.CraftingHistory;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.tracking.crafting.CompletedCrafting;
import pl.kuba6000.ae2webintegration.core.tracking.crafting.CraftingHistoryService;

/**
 * Get crafting history entry.
 *
 * @pathParam gridKey Persistent network identifier.
 * @pathParam entryId History entry ID from the crafting history list.
 * @response 200 {@link Response} Completed or cancelled job details.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters or body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND or TRACKING_NOT_FOUND: network or history entry not found.
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
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/crafting-history/{entryId}")
public final class GetTracking extends IAsyncRequest {

    /** Include product and resource icons and atlas metadata; defaults to false. */
    @QueryParam("icons")
    boolean icons;

    /**
     * @param status {@code OK} for a successful request
     * @param data   completed or cancelled crafting measurements for the selected history entry
     * @param icons  atlas metadata for product and resource references; null when not requested or no pack is available
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull CraftingHistory data, @Nullable IconMappings icons)
        implements IResponseBody {}

    @PathParam("entryId")
    String id;

    @Override
    public void handle() {
        long entryId;
        try {
            entryId = Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        if (entryId < 1 || !Long.toString(entryId)
            .equals(id)) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        CraftingHistoryService service = CoreEngine.getCraftingHistory();
        if (service == null) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        try {
            CompletedCrafting info = service.read(gridKey, entryId);
            if (info == null) {
                deny(ApiStatus.TRACKING_NOT_FOUND);
                return;
            }
            IconPack pack = icons ? CoreEngine.getIconPack() : null;
            IconMappings mappings = pack == null ? null : new IconMappings(pack);
            respondDetached(
                HttpURLConnection.HTTP_OK,
                new Response(ApiStatus.OK, CraftingHistory.capture(info, mappings), mappings));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
