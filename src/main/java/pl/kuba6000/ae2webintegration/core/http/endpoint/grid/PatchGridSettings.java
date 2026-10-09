package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import java.io.IOException;
import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;

/**
 * Updates selected grid settings.
 * <p>
 * Omitted settings remain unchanged. Returns the settings after applying the update.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @response 200 {@link Response} Updated grid settings.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid. CSRF_REJECTED: cookie or
 *           configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: the grid does not exist.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: the request could not be completed because of an unexpected
 *           failure.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"NO_PERMISSIONS","data":null}
 * @responseExample 404 {"status":"GRID_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 */
@Endpoint(method = HttpMethod.PATCH, path = "/api/grids/{gridKey}/settings")
public final class PatchGridSettings extends IAsyncRequest {

    private static final int MAX_NAME_LENGTH = 128;
    // Explicit ECMAScript whitespace keeps Java 8's older Unicode categories from trimming U+180E.
    // C0/C1 controls, including tabs and line breaks, are rejected before trimming.
    private static final String NAME_WHITESPACE = " \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF";

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   settings after the requested changes have been applied and saved
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull GridSettingsData data) {}

    /** Settings to update; omitted members remain unchanged. */
    public static final class Input {

        /**
         * Whether the grid should collect crafting history.
         *
         * @example true
         */
        @OptionalInput
        public @Nullable Boolean isTracked;

        /**
         * Custom network name; empty clears it. Outer Unicode spaces and U+FEFF are trimmed,
         * matching JavaScript String.trim after control characters are rejected.
         * At most 128 UTF-16 code units after trimming. C0/C1 controls (U+0000–001F and
         * U+007F–009F) are rejected even at the edges. Null is invalid; omission preserves the name.
         *
         * @example Factory
         */
        @OptionalInput
        public @Nullable String name;
    }

    @Body
    @SuppressWarnings("NotNullFieldNotInitialized") // Bound before the request is handled.
    private @NotNull Input input;

    @Override
    public void handle() {
        String name;
        try {
            name = input.name == null ? null : normalizeName(input.name);
        } catch (IllegalArgumentException e) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        if (gridKey == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        GridIdentityRegistry registry = CoreEngine.GRID_IDENTITIES;
        synchronized (registry) {
            GridPersistentData data = registry.getPersistentData(gridKey);
            if (data == null) {
                deny(ApiStatus.GRID_NOT_FOUND);
                return;
            }
            GridSettingsData settings = data.getSettings();
            try {
                if (name != null) settings.setName(name);
                if (input.isTracked != null) {
                    settings.setTracked(input.isTracked);
                }
                if (input.name != null || input.isTracked != null) registry.saveIfDirty();
                // Completion serializes under the same monitor used by settings mutations and file writes.
                respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, settings));
            } catch (IOException e) {
                deny(ApiStatus.INTERNAL_ERROR);
            }
        }
    }

    private static @NotNull String normalizeName(@NotNull String name) {
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) throw new IllegalArgumentException("Control in grid name");
        }
        int start = 0;
        int end = name.length();
        while (start < end && NAME_WHITESPACE.indexOf(name.charAt(start)) >= 0) start++;
        while (end > start && NAME_WHITESPACE.indexOf(name.charAt(end - 1)) >= 0) end--;
        if (end - start > MAX_NAME_LENGTH) throw new IllegalArgumentException("Grid name is too long");
        return name.substring(start, end);
    }
}
