package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.sun.net.httpserver.HttpExchange;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
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
import pl.kuba6000.ae2webintegration.core.terminal.TerminalState;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalStreams;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;

/**
 * Lists stored and craftable resources on a grid.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @response 200 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: the grid does not exist.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: the request could not be completed because of an unexpected
 *           failure.
 * @response 503 {@link ErrorResponse} SERVER_BUSY, SERVER_STOPPING or TIMEOUT: the game-thread request could not
 *           complete.
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
public final class GetItems extends ISyncedRequest {

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   stored and craftable resource rows; empty when the grid exposes no resources
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<StoredResource> data, @Nullable IconMappings icons,
        @Nullable TerminalState.View view, @Nullable String subscription) {

        public Response(ApiStatus status, List<StoredResource> data, IconMappings icons) {
            this(status, data, icons, null, null);
        }
    }

    /** Include atlas references. Absent or false skips all native icon normalization. */
    @QueryParam("icons")
    private boolean icons;

    /** Reserve a live subscription together with the returned snapshot. */
    @QueryParam("live")
    private boolean live;
    private @Nullable String subscription;

    /** Called by the router after the game-thread operation; only detached state is read here. */
    public ApiResponse finishResponse(Consumer<ISyncedRequest> dispatch,
        Function<HttpExchange, AE2Controller.RequestContext> authenticate) {
        ApiResponse response = getResponse();
        if (!context.isLifecycleUnchanged()) return ApiResponse.error(ApiStatus.SERVER_STOPPING);
        if (subscription == null) return response;
        PermissionCheck permission = new PermissionCheck(gridKey, grid);
        permission.init(context);
        dispatch.accept(permission);
        ApiResponse permissionResult = permission.getResponse();
        AE2Controller.RequestContext credentials = authenticate.apply(context.getExchange());
        if (permissionResult.httpStatus() != HttpURLConnection.HTTP_OK || credentials == null
            || !credentials.getPrincipal()
                .equals(context.getPrincipal())) {
            TerminalState terminals = CoreEngine.getTerminalState();
            if (terminals != null) terminals.cancel(subscription);
            return permissionResult.httpStatus() != HttpURLConnection.HTTP_OK ? permissionResult
                : ApiResponse.error(ApiStatus.UNAUTHORIZED);
        }
        return response;
    }

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        StorageSnapshot shared;
        try {
            if (live) {
                CoreEngine.prepareTerminal(grid, icons);
                shared = null;
            } else shared = CoreEngine.observeStorage(grid, icons);
        } catch (RuntimeException failedObservation) {
            // The coordinator logs the failed attempt and owns retry timing; do not enumerate again here.
            deny(ApiStatus.INTERNAL_ERROR);
            return;
        }
        if (live && !context.getExchange()
            .getRequestMethod()
            .equals("HEAD")) {
            TerminalState terminals = CoreEngine.getTerminalState();
            IconPack pack = icons ? CoreEngine.getIconPack() : null;
            TerminalState.Bootstrap bootstrap = terminals == null ? null
                : terminals.reserve(
                    gridKey,
                    context.getPrincipal(),
                    icons,
                    pack == null ? null : pack.packId(),
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
                        TerminalStreams
                            .resources(bootstrap.rows(), bootstrap.descriptions(), bootstrap.unkeyed(), mappings),
                        mappings,
                        bootstrap.view(),
                        subscription));
            }
            return;
        }
        if (shared != null) {
            IconPack pack = icons ? CoreEngine.getIconPack() : null;
            IconMappings mappings = pack == null ? null : new IconMappings(pack);
            List<StoredResource> items = TerminalStreams
                .resources(shared.rows(), shared.descriptions(), shared.unkeyed(), mappings);
            respondDetached(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, items, mappings));
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

        StorageCapture capture = storageGrid.web$getStorageCapture();
        if (capture != null) capture.retainIdentities(listing);
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
            if (mappings != null) icon = mappings.resolve(identity, AE2Controller.itemIdentities);

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

    private static final class PermissionCheck extends ISyncedRequest {

        private final IAEGrid expectedGrid;

        private PermissionCheck(StableKey key, IAEGrid expectedGrid) {
            gridKey = key;
            this.expectedGrid = expectedGrid;
        }

        @Override
        protected void handle(IAEGrid current) {
            if (current == null || current != expectedGrid) deny(ApiStatus.GRID_NOT_FOUND);
            else done();
        }
    }

}
