package pl.kuba6000.ae2webintegration.core.http.endpoint.icons;

import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;

/**
 * Reports whether the server has a compatible icon pack installed. The manifest remains server-side.
 * 
 * @response 200 {@link Response} Current availability.
 * @response 400 {@link ErrorResponse} Invalid request body.
 * @response 401 {@link ErrorResponse} Authentication required.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure.
 * @response 503 {@link ErrorResponse} Server stopping.
 * @responseExample 200 {"status":"OK","data":{"available":false,"packId":null,"width":0,"height":0}}
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_STOPPING","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/icon-pack")
public final class GetIconPack extends IAsyncRequest {

    @Desugar
    public record Availability(boolean available, @Nullable String packId, int width, int height) {}

    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull Availability data) {}

    @Override
    public void handle() {
        if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration())) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        IconPack pack = CoreEngine.getIconPack();
        context.getExchange()
            .getResponseHeaders()
            .set("Cache-Control", "private, no-store");
        respond(
            HttpURLConnection.HTTP_OK,
            new Response(
                ApiStatus.OK,
                new Availability(
                    pack != null,
                    pack == null ? null : pack.packId(),
                    pack == null ? 0 : IconPack.CONTENT_SIZE,
                    pack == null ? 0 : IconPack.CONTENT_SIZE)));
    }
}
