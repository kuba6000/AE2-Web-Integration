package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.StoredResource;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.live.LiveSnapshotRequest;
import pl.kuba6000.ae2webintegration.core.live.LiveView;
import pl.kuba6000.ae2webintegration.core.terminal.StorageTopic;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;

/**
 * List resources.
 *
 * <p>
 * Returns stored and craftable resources. Set {@code live=true} to also receive a snapshot version
 * and a single-use subscription for {@code GET /api/grids/{gridKey}/items/events}.
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 200 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters or body.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: network not found.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} SERVER_BUSY, SERVER_STOPPING or TIMEOUT: server unavailable or request timed out.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"NO_PERMISSIONS","data":null}
 * @responseExample 404 {"status":"GRID_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/items")
public final class GetItems extends LiveSnapshotRequest {

    /**
     * @param status       {@code OK} for a successful request
     * @param data         stored and craftable resources; empty when none are available
     * @param icons        icon atlas metadata; null when not requested or unavailable
     * @param view         snapshot version for live updates; null unless live was requested
     * @param subscription single-use stream token; null unless live was requested
     * @param recent       recent tracking summaries; null when unavailable
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<StoredResource> data, @Nullable IconMappings icons,
        @Nullable LiveView view, @Nullable String subscription, @Nullable RecentResourceActivity.Summary recent)
        implements IResponseBody {

        public Response(ApiStatus status, List<StoredResource> data, IconMappings icons) {
            this(status, data, icons, null, null, null);
        }
    }

    /** Include resource icons and atlas metadata; defaults to false. */
    @QueryParam("icons")
    boolean icons;

    @Override
    protected void cancel(String token) {
        TerminalRegistry terminals = CoreEngine.getTerminalRegistry();
        if (terminals != null) terminals.cancel(token);
    }

    @Override
    protected void handle(IAEGrid grid) {
        if (replace != null && replace.length() != 36) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        StorageSnapshot shared;
        try {
            if (live) {
                CoreEngine.prepareTerminal(grid);
                shared = null;
            } else shared = CoreEngine.observeStorage(grid);
        } catch (RuntimeException failedObservation) {
            // The coordinator logs the failed attempt and owns retry timing; do not enumerate again here.
            deny(ApiStatus.INTERNAL_ERROR);
            return;
        }
        if (live && !context.getExchange()
            .getRequestMethod()
            .equals("HEAD")) {
            TerminalRegistry terminals = CoreEngine.getTerminalRegistry();
            IconPack pack = icons ? CoreEngine.getIconPack() : null;
            TerminalRegistry.Bootstrap bootstrap = terminals == null ? null
                : terminals.reserve(
                    gridKey,
                    context.getSession(),
                    icons,
                    pack == null ? null : pack.packId(),
                    replace,
                    System.currentTimeMillis());
            if (bootstrap == null) {
                deny(ApiStatus.SERVER_BUSY);
            } else {
                subscription = bootstrap.subscription();
                IconMappings mappings = pack == null ? null : new IconMappings(pack);
                respondDetached(
                    HttpURLConnection.HTTP_OK,
                    new Response(
                        ApiStatus.OK,
                        StorageTopic
                            .resources(bootstrap.rows(), bootstrap.descriptions(), bootstrap.unkeyed(), mappings),
                        mappings,
                        bootstrap.view(),
                        subscription,
                        bootstrap.recent()));
            }
            return;
        }
        if (shared != null) {
            IconPack pack = icons ? CoreEngine.getIconPack() : null;
            IconMappings mappings = pack == null ? null : new IconMappings(pack);
            List<StoredResource> items = StorageTopic
                .resources(shared.rows(), shared.descriptions(), shared.unkeyed(), mappings);
            TerminalRegistry terminals = CoreEngine.getTerminalRegistry();
            respondDetached(
                HttpURLConnection.HTTP_OK,
                new Response(
                    ApiStatus.OK,
                    items,
                    mappings,
                    null,
                    null,
                    terminals == null ? null : terminals.recent(gridKey)));
            return;
        }
        IAEStorageGrid storageGrid = grid.web$getStorageGrid();
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        IStackList storageList = storageGrid.web$getStorageList();
        ArrayList<StoredResource> items = new ArrayList<>();
        Set<IAEKey> listed = new HashSet<>();
        ItemIdentityRegistry.Listing listing = AE2Controller.itemIdentities.beginListing(grid);
        IconPack pack = icons ? CoreEngine.getIconPack() : null;
        IconMappings mappings = pack == null ? null : new IconMappings(pack);

        for (IAEGenericStack stack : storageList.web$stacks()) {
            addItem(items, stack, grid, listing, mappings);
            listed.add(stack.web$what());
        }

        for (IAEKey craftable : craftingGrid.web$getCraftables(null)) {
            if (!listed.add(craftable)) {
                continue;
            }
            addItem(items, AE2Controller.AE2Interface.web$stackOf(craftable, 0), grid, listing, mappings);
        }

        listing.commit();
        respondDetached(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, items, mappings));
    }

    private static void addItem(ArrayList<StoredResource> items, IAEGenericStack stack, IAEGrid grid,
        ItemIdentityRegistry.Listing listing, @Nullable IconMappings mappings) {
        IAEKey key = stack.web$what();

        String registryPath = key.web$getRegistryPath();
        String displayName = key.web$getDisplayName();
        long quantity = stack.web$amount();
        boolean craftable = key.web$isCraftable(grid);
        StableKey itemKey = null;
        IconMappings.Reference icon = null;

        try {
            StableKey identity = listing.remember(key);
            itemKey = identity;
            if (identity != null && mappings != null) icon = mappings.resolve(identity, AE2Controller.itemIdentities);

        } catch (RuntimeException unavailable) {
            // Optional identity or icon failures must not hide available resources or a successfully captured key.
        }

        items.add(
            new StoredResource(
                key.web$getRegistryNamespace(),
                registryPath,
                displayName,
                key.web$getComponentCount(),
                key.web$getDamage(),
                key.web$getResourceType(),
                quantity,
                craftable,
                itemKey,
                icon));
    }

}
