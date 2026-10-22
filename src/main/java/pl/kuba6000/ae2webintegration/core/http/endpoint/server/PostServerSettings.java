package pl.kuba6000.ae2webintegration.core.http.endpoint.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.electronwill.nightconfig.core.io.ParsingException;
import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.EditableServerSettings;
import pl.kuba6000.ae2webintegration.core.config.ServerSettingsSnapshot;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;

/**
 * Saves and applies the complete editable server configuration without restarting the web listener.
 * Listener port, administrator password, public mode, localhost trust, proxy trust and request limit are file-only.
 * Requests contain revision, editable settings and optional secrets updates; readOnly metadata is rejected.
 * Omit discordWebhook or ntfyPassword to preserve it, send a nonempty string to replace it, or an empty
 * string to clear it. Explicit null values are invalid; saved secrets are never returned.
 * 
 * @response 200 {@link Response} Saved configuration and its new opaque configuration revision.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid, missing, unknown or file-only settings.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: administrator required; CSRF_REJECTED: missing request marker.
 * @response 409 {@link ErrorResponse} CONFIG_CHANGED: active configuration changed since the settings were read; fetch
 *           it again before saving.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: JSON required.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: rate limit exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: configuration could not be saved.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: originating listener has stopped.
 * @responseExample 409 {"status":"CONFIG_CHANGED","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/server/settings")
public final class PostServerSettings extends IAsyncRequest {

    @Body
    private @NotNull Input input;

    /** Complete editable values, current configuration revision and optional write-only notification secret updates. */
    @Desugar
    public record Input(@NotNull String revision, @NotNull EditableServerSettings settings,
        @OptionalInput @Nullable EditableServerSettings.SecretUpdates secrets) {}

    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull ServerSettingsSnapshot data) implements IResponseBody {}

    @Override
    public void handle() {
        if (!context.isAdmin()) {
            deny(ApiStatus.NO_PERMISSIONS);
            return;
        }
        // Serialize publication and dependent policy application without holding a game-thread monitor.
        synchronized (Config.class) {
            if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration())) {
                deny(ApiStatus.SERVER_STOPPING);
                return;
            }
            ServerSettingsSnapshot saved;
            try {
                saved = Config.saveServerSettings(input.revision(), input.settings(), input.secrets());
            } catch (ParsingException invalidFile) {
                deny(ApiStatus.INTERNAL_ERROR);
                return;
            } catch (IllegalArgumentException invalid) {
                deny(ApiStatus.BAD_PARAM);
                return;
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not save server configuration", failure);
            }
            if (saved == null) {
                deny(ApiStatus.CONFIG_CHANGED);
                return;
            }
            CoreEngine.applyServerConfig();
            respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, saved));
        }
    }
}
