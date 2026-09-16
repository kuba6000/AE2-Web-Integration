package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;

/**
 * Reads grid settings.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @response 200 {@link Response} Current grid settings.
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
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/settings")
public final class GetGridSettings extends IAsyncRequest {

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   current settings retained for the selected grid identity
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull GridSettingsData data) {}

    @Override
    public void handle() {
        if (gridKey == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        GridIdentityRegistry registry = CoreEngine.GRID_IDENTITIES;
        synchronized (registry) {
            GridPersistentData data = registry.getPersistentData(gridKey);
            if (data == null) {
                deny(ApiStatus.GRID_NOT_FOUND);
                return;
            }
            GridSettingsData settings = data.getSettings();
            // Completion serializes under the same monitor used by settings mutations and file writes.
            respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, settings));
        }
    }
}
