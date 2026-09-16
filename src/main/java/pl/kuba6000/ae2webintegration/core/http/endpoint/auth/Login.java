package pl.kuba6000.ae2webintegration.core.http.endpoint.auth;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;

/**
 * Creates a web session from account credentials.
 * <p>
 * The returned token can be used as a Bearer credential. This API does not set browser cookies.
 * 
 * @response 200 {@link Response} Session created.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} INVALID_USER or INVALID_PASSWORD.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: the request could not be completed because of an unexpected
 *           failure.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: session publication was interrupted by server shutdown.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"INVALID_PASSWORD","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_STOPPING","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/auth/login", authenticated = false)
public final class Login extends IAsyncRequest {

    /** Account credentials and the requested session lifetime. */
    @Body
    @NotNull
    @SuppressWarnings("NotNullFieldNotInitialized") // Bound before handle().
    private Input input;

    /**
     * Login credentials.
     * 
     * @param username   account name, or Admin
     * @param password   account password
     * @param rememberMe keep the session for seven days instead of one hour; omitted means false
     * @example username ExamplePlayer
     * @example password example-password
     * @example rememberMe false
     */
    @Desugar
    public record Input(String username, String password, @OptionalInput @Nullable Boolean rememberMe) {}

    /**
     * Session credentials and display information.
     *
     * @param token      opaque session credential for the {@code Authorization: Bearer} header; valid for one hour,
     *                   or seven days when {@code rememberMe} was requested
     * @param username   authenticated account's display name; {@code Admin} for administrator sessions
     * @param isAdmin    whether this session has administrator access to all grids
     * @param isOutdated whether update checking is enabled and a newer mod release is currently known
     * @example token exampleSessionTokenForDocumentationOnly
     * @example username ExamplePlayer
     * @example isAdmin false
     * @example isOutdated false
     */
    @Desugar
    public record Session(String token, String username, boolean isAdmin, boolean isOutdated) {}

    /**
     * Login result.
     * 
     * @param status {@code OK} when a session was created
     * @param data   session token and information about the authenticated account
     * @example status OK
     */
    @Desugar
    public record Response(ApiStatus status, Session data) {}

    @Override
    public void handle() {
        respond(
            AE2Controller
                .loginApi(context, input.username(), input.password(), Boolean.TRUE.equals(input.rememberMe())));
    }
}
