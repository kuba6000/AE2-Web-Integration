package pl.kuba6000.ae2webintegration.core.http.endpoint.cpu;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.cpu.CpuTopic;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.GenerateHttpBinding;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.live.LiveEventsRequest;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;

/**
 * Opens the live stream of a crafting CPU.
 * The single-use subscription token is returned by a live snapshot, GET cpus/{cpuKey} with live=true.
 * Updates contain the absolute work of changed resources, with definitions only for new or changed resources.
 * Measured time is sent as of the update; while crafting is true a client extends it with its own clock, and derives
 * timing ratios itself. Updates are sent only when work, counts or the crafting flag change, never for clock progress.
 * Removed keys disappear from the CPU. An optional unkeyed array replaces all unkeyed resources, and an optional cpu
 * object replaces the CPU header without items.
 * Reset and invalidate require a new snapshot and subscription; a job starting, merging, completing or being cancelled
 * invalidates, as does a CPU that no longer exists. Revoked indicates lost access.
 * Reconnection never replays intermediate events. Last-Event-ID is not used.
 * Heartbeat events must be acknowledged by POST to this path with the subscription token.
 * Renew about every 30 seconds; a stream without acknowledgement expires after 90 seconds.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @pathParam cpuKey Stable crafting CPU identifier.
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
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/cpus/{cpuKey}/events")
@GenerateHttpBinding
public final class GetCPUEvents extends LiveEventsRequest {

    @PathParam("cpuKey")
    @NotNull
    StableKey cpuKey;

    @Override
    protected @NotNull LiveTopic.Key topicKey() {
        return CpuTopic.keyOf(gridKey, cpuKey);
    }
}
