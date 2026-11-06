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
 * Get server settings.
 * <p>
 * Requires administrator or trusted localhost access and general.allow_server_settings_editing enabled in config.toml.
 * Returns editable settings, read-only settings and the revision needed to save changes. Secret values are never
 * returned.
 * 
 * @response 200 {@link Response} Current server settings and revision.
 * @response 400 {@link ErrorResponse} BAD_PARAM: unexpected request body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: administrator required;
 *           SERVER_SETTINGS_DISABLED: web settings access is disabled in the active configuration.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: body exceeds 8192 bytes.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: rate limit exceeded.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: server stopping.
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
        ServerSettingsSnapshot snapshot;
        synchronized (Config.class) {
            if (!context.isLifecycleUnchanged()) {
                deny(ApiStatus.SERVER_STOPPING);
                return;
            }
            if (!Config.INSTANCE.general.allowServerSettingsEditing) {
                deny(ApiStatus.SERVER_SETTINGS_DISABLED);
                return;
            }
            snapshot = Config.readServerSettings();
        }
        respondDetached(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, snapshot));
    }
}
