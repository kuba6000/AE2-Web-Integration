package pl.kuba6000.ae2webintegration.core.http.endpoint.auth;

import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.auth.AuthService.RegistrationResult;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;

/**
 * Begins account registration for an online player.
 * <p>
 * Finish registration in Minecraft using the returned token and the auth command.
 * 
 * @response 202 {@link Response} Confirmation token issued; registration is still pending.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields. INVALID_PASSWORD: registration password could not be accepted.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 409 {@link ErrorResponse} Player not online.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: the request could not be completed because of an unexpected
 *           failure.
 * @response 503 {@link ErrorResponse} SERVER_BUSY, SERVER_STOPPING or TIMEOUT: the game-thread request could not
 *           complete.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 409 {"status":"NOT_ONLINE","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/auth/register", authenticated = false)
public final class Register extends IAsyncRequest {

    /** Credentials for the account being registered by an online Minecraft player. */
    @Body
    @NotNull
    @SuppressWarnings("NotNullFieldNotInitialized") // Bound before handle().
    private Input input;

    /**
     * Requested account credentials.
     * 
     * @param username online Minecraft player name
     * @param password password for the web account
     * @example username ExamplePlayer
     * @example password example-password
     */
    @Desugar
    public record Input(String username, String password) {}

    /**
     * Pending registration to confirm from the requested player's Minecraft account.
     *
     * @param token confirmation value for {@code /ae2webintegration auth <token>}; this is not a Bearer session
     *              token, and completing registration does not log the web client in
     * @example token exampleConfirmationTokenForDocumentationOnly
     */
    @Desugar
    public record Confirmation(String token) {}

    /**
     * Registration result.
     * 
     * @param status {@code OK} when the confirmation token was issued; registration is still pending
     * @param data   token required to confirm the registration in Minecraft
     * @example status OK
     */
    @Desugar
    public record Response(ApiStatus status, Confirmation data) {}

    @Override
    public void handle() {
        RegistrationResult result = AuthService
            .register(context.getLifecycleGeneration(), input.username(), input.password());
        if (result.status() == ApiStatus.INVALID_PASSWORD) {
            respond(HttpURLConnection.HTTP_BAD_REQUEST, new ErrorResponse(result.status(), null));
        } else if (result.status() != ApiStatus.OK) {
            deny(result.status());
        } else {
            respond(HttpURLConnection.HTTP_ACCEPTED, new Response(ApiStatus.OK, new Confirmation(result.token())));
        }
    }
}
