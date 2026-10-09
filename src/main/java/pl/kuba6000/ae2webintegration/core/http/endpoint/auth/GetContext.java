package pl.kuba6000.ae2webintegration.core.http.endpoint.auth;

import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Authentication;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;

/**
 * Returns public application metadata and the current authenticated display identity.
 * <p>
 * Authentication is optional. Valid session credentials or trusted localhost access supply the user;
 * absent, invalid or expired credentials return a null user. This response contains no session token.
 *
 * @response 200 {@link Response} Current application metadata and optional user.
 * @response 400 {@link ErrorResponse} Invalid request body.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure.
 * @response 503 {@link ErrorResponse} Server stopping.
 * @responseExample 200
 *                  {"status":"OK","data":{"publicMode":true,"modVersion":"1.2.0","isOutdated":false,"user":null,"capabilities":{}}}
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_STOPPING","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/context", authentication = Authentication.OPTIONAL)
public final class GetContext extends IAsyncRequest {

    /**
     * Display information only; credentials and account identifiers are never included.
     *
     * @param username authenticated display name, including Admin or localhost for those principals
     * @param isAdmin  whether the authenticated principal has administrator access to all grids
     */
    @Desugar
    public record User(@NotNull String username, boolean isAdmin) {}

    /**
     * Application metadata used to initialize the browser interface.
     *
     * @param publicMode   whether individual player web accounts are enabled
     * @param modVersion   native mod version, or null before the platform initializes the core
     * @param isOutdated   whether update checking is enabled and a newer mod release is currently known
     * @param user         authenticated display identity, or null without valid session or trusted localhost access
     * @param capabilities native feature support for authenticated users; empty for anonymous requests. Keys include
     *                     craftingLightMode and craftingPlanSteps (per-resource crafting step counts).
     * @keyExample capabilities craftingLightMode
     */
    @Desugar
    public record Metadata(boolean publicMode, @Nullable String modVersion, boolean isOutdated, @Nullable User user,
        @NotNull Map<String, Boolean> capabilities) {}

    /**
     * Application context result.
     *
     * @param status OK when metadata is available
     * @param data   public settings and the optional authenticated display identity
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull Metadata data) {}

    @Override
    public void handle() {
        if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration())) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        WebPrincipal principal = context.getPrincipal();
        User user = principal.equals(WebPrincipal.anonymous()) ? null
            : new User(principal.getUsername(), principal.isAdmin());
        Map<String, Boolean> capabilities = Collections.emptyMap();
        if (user != null) {
            capabilities = new LinkedHashMap<>();
            for (Map.Entry<ServerCapability, Boolean> capability : AE2Controller.AE2Interface.web$getCapabilities()
                .entrySet()) {
                capabilities.put(
                    capability.getKey()
                        .wireName(),
                    capability.getValue());
            }
        }
        respond(
            HttpURLConnection.HTTP_OK,
            new Response(
                ApiStatus.OK,
                new Metadata(
                    Config.INSTANCE.general.publicMode,
                    CoreEngine.getModVersion(),
                    Config.INSTANCE.general.checkForUpdates && CoreEngine.getAvailableUpdate() != null,
                    user,
                    capabilities)));
    }
}
