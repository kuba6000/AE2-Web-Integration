package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import java.io.IOException;
import java.net.HttpURLConnection;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.live.LiveStreams;
import pl.kuba6000.ae2webintegration.core.terminal.StorageTopic;

/**
 * Opens a single-use live subscription returned by GET items?live=true.
 * Updates contain absolute quantities and craftability, with definitions only for new or changed resources.
 * Removed keys disappear from the terminal. An optional unkeyed array replaces all unkeyed resources.
 * Reset and invalidate require a new snapshot and subscription; revoked indicates lost access.
 * Reconnection never replays intermediate events. Last-Event-ID is not used.
 * Heartbeat events must be acknowledged by POST to this path with the subscription token.
 * Renew about every 30 seconds; a stream without acknowledgement expires after 90 seconds.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @response 200 Server-sent ready, delta, heartbeat, reset, invalidate and revoked events.
 * @responseMedia 200 text/event-stream
 * @responseHeader 200 Cache-Control No-store: event streams must not be cached.
 * @response 400 {@link ErrorResponse} Invalid, expired or already used subscription.
 * @response 401 {@link ErrorResponse} Authentication required.
 * @response 403 {@link ErrorResponse} Grid permission denied.
 * @response 404 {@link ErrorResponse} Grid not found.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure before streaming begins.
 * @response 503 {@link ErrorResponse} Stream capacity reached or server unavailable.
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/items/events")
public final class GetItemEvents extends IAsyncRequest {

    /** Single-use token returned with the initial snapshot. Bound to the login session, grid and icon mode. */
    @QueryParam("subscription")
    private @Nullable String subscription;
    /** Include icon references when new or changed resource definitions are sent. */
    @QueryParam("icons")
    private boolean icons;
    private @Nullable LiveStreams streams;

    @Override
    public void handle() {
        if (gridKey == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        if (subscription == null || subscription.length() != 36) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        streams = CoreEngine.getLiveStreams();
        if (streams == null) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        done();
    }

    /** Transfers exchange ownership after the normal request's permission check and completion. */
    public boolean startStream() throws IOException {
        if (getResponse().httpStatus() != HttpURLConnection.HTTP_OK || streams == null || subscription == null)
            return false;
        if (!context.isLifecycleUnchanged()) {
            ApiResponse.error(ApiStatus.SERVER_STOPPING)
                .send(context.getExchange());
            return true;
        }
        if (context.getExchange()
            .getRequestMethod()
            .equals("HEAD")) {
            context.getExchange()
                .getResponseHeaders()
                .set("Content-Type", "text/event-stream; charset=UTF-8");
            context.getExchange()
                .sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
            context.getExchange()
                .close();
        } else streams.open(context, StorageTopic.keyOf(gridKey), subscription, icons);
        return true;
    }
}
