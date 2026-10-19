package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.live.LiveEventsRequest;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;
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
public final class GetItemEvents extends LiveEventsRequest {

    @Override
    protected LiveTopic.Key topicKey() {
        return StorageTopic.keyOf(gridKey);
    }
}
