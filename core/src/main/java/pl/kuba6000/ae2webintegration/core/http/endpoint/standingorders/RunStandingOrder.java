package pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEditor;

/**
 * Run standing order now.
 *
 * <p>
 * Asks the server to check the order at its next tick without waiting for its cooldown or backoff, reading the network
 * once more. The usual conditions still apply: nothing is crafted unless the trigger holds, a CPU is free and the order
 * is not already running. Poll the standing order list for the outcome. Running needs network access and, unless the
 * account is an administrator, the server's allow_players setting, and standing orders enabled on the server. The
 * extra read of the network is spaced by the server and never refused.
 *
 * @pathParam gridKey Persistent network identifier.
 * @pathParam orderKey Standing order identifier.
 * @response 202 {@link Response} The check was queued.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters or body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied, or only administrators may run
 *           standing orders. STANDING_ORDERS_DISABLED: standing orders are disabled on the server. CSRF_REJECTED:
 *           cookie or configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: network not found. STANDING_ORDER_NOT_FOUND: the network has no
 *           such order.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 409 {@link ErrorResponse} STANDING_ORDER_NOT_RUNNABLE: the order is disabled, its stored definition
 *           cannot be read, or standing orders are switched off or unavailable for the network.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: the server is stopping.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"STANDING_ORDERS_DISABLED","data":null}
 * @responseExample 404 {"status":"STANDING_ORDER_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 409 {"status":"STANDING_ORDER_NOT_RUNNABLE","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_STOPPING","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/standing-orders/{orderKey}/run")
public final class RunStandingOrder extends StandingOrderRequest {

    /**
     * @param status {@code OK} when the check was queued
     * @param data   always {@code null}
     * @example status OK
     * @example data null
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @Nullable Void data) implements IResponseBody {}

    @PathParam("orderKey")
    @NotNull
    StableKey orderKey;

    @Override
    public void handle() {
        StandingOrderEditor editor = editor();
        if (editor == null || refusesNewWork()) return;
        try {
            switch (editor.run(gridKey, orderKey)) {
                case NOT_FOUND -> deny(ApiStatus.STANDING_ORDER_NOT_FOUND);
                case NOT_RUNNABLE -> deny(ApiStatus.STANDING_ORDER_NOT_RUNNABLE);
                case QUEUED -> respond(HTTP_ACCEPTED, new Response(ApiStatus.OK, null));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
