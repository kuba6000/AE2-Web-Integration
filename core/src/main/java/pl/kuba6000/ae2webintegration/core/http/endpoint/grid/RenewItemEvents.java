package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

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
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;

/**
 * Renew resource stream.
 *
 * <p>
 * Acknowledge heartbeat events about every 30 seconds to keep an open stream active for another
 * 90 seconds. Use its subscription token and original login session for the same network.
 * An expired subscription requires a new live snapshot.
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 200 {@link Response} Stream lease renewed.
 * @response 400 {@link ErrorResponse} Invalid, unopened, expired or foreign subscription.
 * @response 401 {@link ErrorResponse} Authentication required.
 * @response 403 {@link ErrorResponse} Network access denied or required CSRF header missing.
 * @response 404 {@link ErrorResponse} Network not found.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 415 {@link ErrorResponse} Unsupported request body content type.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure.
 * @response 503 {@link ErrorResponse} Server stopping.
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/items/events")
public final class RenewItemEvents extends IAsyncRequest {

    /**
     * Stream renewal result.
     *
     * @param status {@code OK} for a successful renewal
     * @param data   always {@code null}
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @Nullable Void data) implements IResponseBody {}

    /** Required subscription token of the open stream. */
    @QueryParam("subscription")
    @Nullable
    String subscription;

    @Override
    public void handle() {
        if (gridKey == null || subscription == null || subscription.length() != 36) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        TerminalRegistry terminals = CoreEngine.getTerminalRegistry();
        if (terminals == null || !context.isLifecycleUnchanged()) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        if (!terminals.renew(gridKey, context.getSession(), subscription, System.currentTimeMillis())) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, null));
    }
}
