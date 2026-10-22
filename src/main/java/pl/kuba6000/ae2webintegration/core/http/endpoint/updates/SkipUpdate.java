package pl.kuba6000.ae2webintegration.core.http.endpoint.updates;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;

/**
 * Skips the displayed update for this server installation. Administrator access is required.
 * Newer releases remain eligible for notifications. The installed version remains outdated.
 *
 * @response 200 {@link Response} The skip was saved, or this recommendation was already skipped.
 * @response 400 {@link ErrorResponse} BAD_PARAM: missing, empty or invalid tag.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: administrator required; CSRF_REJECTED: missing request marker.
 * @response 409 {@link ErrorResponse} UPDATE_CHANGED: recommendation unavailable, changed or checking disabled.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: JSON required.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: request rate limit exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: the skip could not be saved.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: request belongs to a stopped server lifecycle.
 * @responseExample 200 {"status":"OK","data":null}
 * @responseExample 409 {"status":"UPDATE_CHANGED","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/updates/skip")
public final class SkipUpdate extends IAsyncRequest {

    @Body
    private @NotNull Input input;

    /** @param tag exact tag supplied by availableUpdate; 1–256 non-whitespace ASCII characters */
    @Desugar
    public record Input(@NotNull String tag) {}

    /**
     * @param status OK after durable save
     * @param data   always null
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @Nullable Void data) implements IResponseBody {}

    @Override
    public void handle() {
        if (!context.getPrincipal()
            .isAdmin()) {
            deny(ApiStatus.NO_PERMISSIONS);
            return;
        }
        String tag = input.tag();
        if (tag.isEmpty() || tag.length() > 256
            || tag.chars()
                .anyMatch(character -> character <= 32 || character >= 127)) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        try {
            ApiStatus result = CoreEngine.skipUpdate(tag, context.getLifecycleGeneration());
            if (result != ApiStatus.OK) {
                deny(result);
                return;
            }
            respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, null));
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to save skipped update", failure);
        }
    }
}
