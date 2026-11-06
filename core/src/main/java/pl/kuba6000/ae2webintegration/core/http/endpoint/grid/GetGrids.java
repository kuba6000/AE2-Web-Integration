package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * List networks.
 *
 * <p>
 * Returns accessible ME networks with CPU counts, tracking status and access information.
 * Unavailable networks are excluded.
 *
 * @response 200 {@link Response} Accessible network summaries.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters or body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} SERVER_BUSY, SERVER_STOPPING or TIMEOUT: server unavailable or request timed out.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids")
public final class GetGrids extends ISyncedRequest {

    /**
     * Accessible ME networks.
     *
     * @param status {@code OK} for a successful request
     * @param data   network summaries; an empty list when no network is accessible
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<GridInfo> data) implements IResponseBody {}

    /**
     * Network summary.
     *
     * @param key               persistent network identifier
     * @param name              custom network name; empty when unnamed
     * @param cpuCount          number of crafting CPUs in the network
     * @param owner             representative owner's username, or {@code N/A} when no owner is known
     * @param isOwned           true for player-authorized access; false for administrator or trusted-local access
     * @param isTrackingEnabled whether crafting tracking is enabled for this network
     * @param accessSources     players with explicit network access, keyed by UUID, and the sources granting that
     *                          access;
     *                          excludes administrator and trusted-local bypass access
     * @example key AAAAAAAAAAAAAAAAAAAAAA
     * @example name Factory
     * @example cpuCount 2
     * @example owner ExamplePlayer
     * @example isOwned true
     * @example isTrackingEnabled true
     * @keyExample accessSources 095be615-a8ad-4c33-8e9c-c7612fbf6c9f
     */
    @Desugar
    public record GridInfo(StableKey key, String name, int cpuCount, String owner, boolean isOwned,
        boolean isTrackingEnabled, Map<UUID, List<GridAccessSource>> accessSources) {

        GridInfo(GridAccess.View view, boolean isOwned, @Nullable PlayerIdentity owner,
            Map<UUID, List<GridAccessSource>> sources, GridSettingsData settings) {
            this(
                view.key(),
                settings.getName(),
                view.grid()
                    .web$getCraftingGrid()
                    .web$getCPUCount(),
                owner == null ? "N/A" : owner.name,
                isOwned,
                settings.isTracked(),
                sources);
        }
    }

    @Override
    public void handle() {
        ArrayList<GridInfo> result = new ArrayList<>();
        for (GridAccess.View view : grids) {
            if (!view.allows(context.getPrincipal())) continue;
            GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(view.key());
            if (data == null) continue;
            result.add(
                new GridInfo(
                    view,
                    !context.isAdmin(),
                    view.grid()
                        .web$getRepresentativeOwner(),
                    view.grid()
                        .web$getPermissions(),
                    data.getSettings()));
        }
        result.sort((first, second) -> {
            int owned = Boolean.compare(second.isOwned(), first.isOwned());
            if (owned != 0) return owned;
            int tracked = Boolean.compare(second.isTrackingEnabled(), first.isTrackingEnabled());
            return tracked != 0 ? tracked : Integer.compare(second.cpuCount(), first.cpuCount());
        });
        // Serialize live permission collections before the server thread completes this request.
        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, result));
    }
}
