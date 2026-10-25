package pl.kuba6000.ae2webintegration.core.http.endpoint.themes;

import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.themes.ThemeCatalog;

/**
 * List themes.
 * <p>
 * Returns installed server themes and the default selection. Theme assets are available at /themes/{id}/{path}.
 * The default is builtin:default or server:{id}; an unavailable default falls back to builtin:default.
 *
 * @response 200 {@link Response} Installed packages and the effective server default.
 * @response 400 {@link ErrorResponse} Invalid request body.
 * @response 401 {@link ErrorResponse} Authentication required.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure.
 * @response 503 {@link ErrorResponse} Server stopping.
 * @responseExample 200 {"status":"OK","data":{"defaultTheme":"builtin:default","themes":[]}}
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_STOPPING","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/themes")
public final class GetThemes extends IAsyncRequest {

    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull ThemeCatalog.Description data)
        implements IResponseBody {}

    @Override
    public void handle() {
        if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration())) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        respond(
            HttpURLConnection.HTTP_OK,
            new Response(
                ApiStatus.OK,
                CoreEngine.getThemes()
                    .describe(Config.INSTANCE.general.defaultTheme)));
    }
}
