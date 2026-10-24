package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.live.LiveEventsRequest;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;
import pl.kuba6000.ae2webintegration.core.terminal.StorageTopic;

/**
 * Stream resource updates.
 *
 * <p>
 * Open the single-use subscription from {@code GET /api/grids/{gridKey}/items?live=true}
 * within 10 seconds, using the same login session. Renew it by POSTing to this path about every 30 seconds; it expires
 * after 90 seconds without renewal.
 *
 * <p>
 * After disconnection, reset or invalidate, fetch a new live snapshot and subscription.
 * Events are not replayed; Last-Event-ID is ignored. Delta quantities are absolute values, not increments.
 * Apply removed keys, then definitions, then updates. A generation mismatch or an unknown update key
 * requires a new snapshot; ignore revisions that are not newer than the current revision.
 *
 * @event ready The stream is open; keep the initial snapshot.
 * @event delta {@link pl.kuba6000.ae2webintegration.core.terminal.StorageTopic.Delta} Apply resource changes to the
 *        snapshot.
 * @event heartbeat The connection is alive; renew by POST with the subscription token about every 30 seconds.
 * @event reset Close the stream and fetch a new live snapshot and subscription.
 * @event invalidate The snapshot is no longer valid; close the stream and fetch a new live snapshot and subscription.
 * @event revoked Access or login expired; close the stream and restore access before reconnecting.
 * @eventExample delta
 *               {"view":{"generation":"1.0","revision":"2"},"updates":[{"itemKey":"AAAAAAAAAAAAAAAAAAAAAA","quantity":64,"craftable":true}],"definitions":[],"removed":[],"unkeyed":null,"icons":null,"recent":null}
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 200 Server-sent ready, delta, heartbeat, reset, invalidate and revoked events.
 * @responseMedia 200 text/event-stream
 * @responseHeader 200 Cache-Control No-store: event streams must not be cached.
 * @response 400 {@link ErrorResponse} Invalid, expired or already used subscription.
 * @response 401 {@link ErrorResponse} Authentication required.
 * @response 403 {@link ErrorResponse} Network access denied.
 * @response 404 {@link ErrorResponse} Network not found.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure before streaming begins.
 * @response 503 {@link ErrorResponse} Stream capacity reached or server unavailable.
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/items/events")
public final class GetItemEvents extends LiveEventsRequest {

    @Override
    protected @NotNull LiveTopic.Key topicKey() {
        return StorageTopic.keyOf(gridKey);
    }
}
