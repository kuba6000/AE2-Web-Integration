package pl.kuba6000.ae2webintegration.core.http.endpoint.auth;

import java.net.HttpURLConnection;

import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;

/**
 * Revokes the current web session.
 * <p>
 * Browser clients return to the website after logout so its cookie is cleared at the correct mount path.
 * 
 * @response 200 {@link Response} Session revoked.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} CSRF_REJECTED: cookie or configured-local mutations require X-AE2-Request:
 *           true.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: the request could not be completed because of an unexpected
 *           failure.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"CSRF_REJECTED","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/auth/logout")
public final class Logout extends IAsyncRequest {

    /**
     * Logout result.
     * 
     * @param status {@code OK} when the logout request completed
     * @param data   always {@code null}; logout returns no additional data
     * @example status OK
     * @example data null
     */
    @Desugar
    public record Response(ApiStatus status, @Nullable Void data) {}

    @Override
    public void handle() {
        AE2Controller.logoutApi(context);
        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, null));
    }
}
