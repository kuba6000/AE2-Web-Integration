package pl.kuba6000.ae2webintegration.core.http.endpoint.tracking;

import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.CraftingHistory;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

/**
 * Reads a crafting history entry.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @pathParam entryId Runtime history entry identifier.
 * @response 200 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND or TRACKING_NOT_FOUND: the requested resource does not exist.
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
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/crafting-history/{entryId}")
public final class GetTracking extends IAsyncRequest {

    /** Include product and resource icon references and atlas metadata; omitted means false. */
    @QueryParam("icons")
    private boolean icons;

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   completed or cancelled crafting measurements for the selected history entry
     * @param icons  atlas metadata for product and resource references; null when not requested or no pack is available
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull CraftingHistory data, @Nullable IconMappings icons) {}

    @PathParam("entryId")
    private int id;

    @Override
    public void handle() {
        if (grid == null) {
            // The grid is real - access was checked - it simply has no tracking data at all.
            deny(ApiStatus.TRACKING_NOT_FOUND);
            return;
        }

        AE2JobTracker.JobTrackingInfo info = grid.trackingInfo.trackingInfos.get(id);
        if (info == null) {
            deny(ApiStatus.TRACKING_NOT_FOUND);
            return;
        }

        IconPack pack = icons ? CoreEngine.getIconPack() : null;
        IconMappings mappings = pack == null ? null : new IconMappings(pack);
        respond(
            HttpURLConnection.HTTP_OK,
            new Response(ApiStatus.OK, CraftingHistory.capture(info, mappings), mappings));
    }

}
