package pl.kuba6000.ae2webintegration.core.http.endpoint.tracking;

import static java.net.HttpURLConnection.HTTP_OK;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

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
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

/**
 * Lists resources with retained storage history for this network.
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 200 {@link Response} Retained resources page.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: network not found.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: request body exceeds the limit.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: request rate limit exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: history unavailable.
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
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/tracking/resources")
public final class GetTrackingResources extends IAsyncRequest {

    /** Page size, from 1 to 100. */
    @QueryParam("limit")
    int limit = 50;

    /** Exclusive cursor returned by the previous page. */
    @QueryParam("after")
    String after;

    /** ASCII case-insensitive display name or registry identifier substring. */
    @QueryParam("search")
    String search;

    /**
     * @param status    request status
     * @param data      retained resource descriptions
     * @param nextAfter exclusive cursor for the next page, or null
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<ResourceDescriptor> data,
        @Nullable String nextAfter) implements IResponseBody {}

    @Override
    public void handle() {
        Long cursor = null;
        if (after != null) {
            try {
                cursor = Long.parseLong(after);
            } catch (NumberFormatException invalid) {
                deny(ApiStatus.BAD_PARAM);
                return;
            }
            if (cursor < 1 || !Long.toString(cursor)
                .equals(after)) {
                deny(ApiStatus.BAD_PARAM);
                return;
            }
        }
        if (limit < 1 || limit > 100 || search != null && search.length() > 256) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        StorageTrackingService service = CoreEngine.getStorageTracking();
        if (service == null) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        try {
            IStorageHistoryStore.ResourcePage page = service.resources(gridKey, cursor, search, limit);
            respondDetached(
                HTTP_OK,
                new Response(
                    ApiStatus.OK,
                    page.entries(),
                    page.nextAfter() == null ? null : Long.toString(page.nextAfter())));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
