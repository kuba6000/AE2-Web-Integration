package pl.kuba6000.ae2webintegration.core.http.endpoint.cpu;

import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.cpu.CpuRegistry;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Acknowledges an attached crafting CPU stream and renews its lease for 90 seconds.
 * Clients acknowledge heartbeat events about every 30 seconds while viewing the stream.
 * Tokens remain bound to their original login session, grid and CPU; expiry requires a fresh live snapshot.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @pathParam cpuKey Stable crafting CPU identifier.
 * @response 200 {@link Response} Stream lease renewed.
 * @response 400 {@link ErrorResponse} Invalid, unopened, expired or foreign subscription.
 * @response 401 {@link ErrorResponse} Authentication required.
 * @response 403 {@link ErrorResponse} Grid permission denied or required CSRF header missing.
 * @response 404 {@link ErrorResponse} Grid not found.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 415 {@link ErrorResponse} Unsupported request body content type.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure.
 * @response 503 {@link ErrorResponse} Server stopping.
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/cpus/{cpuKey}/events")
public final class RenewCpuEvents extends IAsyncRequest {

    @Desugar
    public record Response(@NotNull ApiStatus status, @Nullable Void data) {}

    /** Token belonging to the attached stream being acknowledged. */
    @QueryParam("subscription")
    private @Nullable String subscription;

    @PathParam("cpuKey")
    private @NotNull StableKey cpuKey;

    @Override
    public void handle() {
        if (gridKey == null || subscription == null || subscription.length() != 36) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        CpuRegistry registry = CoreEngine.getCpuRegistry();
        if (registry == null || !context.isLifecycleUnchanged()) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        if (!registry.renew(gridKey, cpuKey, context.getSession(), subscription, System.currentTimeMillis())) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, null));
    }
}
