package pl.kuba6000.ae2webintegration.core.http.endpoint.server;

import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ServerSettingsSnapshot;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;

/**
 * Reads the active editable server configuration from memory. Administrator or trusted localhost access is required.
 * Read-only metadata includes listener port, access policy, proxy trust, request limit and configured-secret flags.
 * Administrator password, Discord webhook and ntfy password values are never returned.
 * Read-only allowServerSettingsEditing reports whether web saves are enabled; reading remains available when disabled.
 * 
 * @response 200 {@link Response} Active editable configuration, file-only metadata and opaque configuration revision.
 * @response 400 {@link ErrorResponse} BAD_PARAM: unexpected request body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: administrator required.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: body exceeds 8192 bytes.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: rate limit exceeded.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: originating listener has stopped.
 */
@Endpoint(method = HttpMethod.GET, path = "/api/server/settings")
public final class GetServerSettings extends IAsyncRequest {

    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull ServerSettingsSnapshot data) implements IResponseBody {}

    @Override
    public void handle() {
        if (!context.isAdmin()) {
            deny(ApiStatus.NO_PERMISSIONS);
            return;
        }
        if (!context.isLifecycleUnchanged()) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        respondDetached(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, Config.readServerSettings()));
    }
}
