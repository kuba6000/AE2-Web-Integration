package pl.kuba6000.ae2webintegration.core.live;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;

/** A snapshot request that can reserve a live subscription together with its snapshot. */
public abstract class LiveSnapshotRequest extends ISyncedRequest {

    /** Reserve a live subscription together with the returned snapshot. */
    @QueryParam("live")
    protected boolean live;
    /** Replace this login's previous subscription to the same view when reserving a new live snapshot. */
    @QueryParam("replace")
    protected @Nullable String replace;
    protected @Nullable String subscription;

    /** Releases a reservation whose snapshot could not be delivered. */
    protected abstract void cancel(String token);

    /** Called by the router after the game-thread operation; only detached state is read here. */
    public final ApiResponse finishResponse() {
        ApiResponse response = getResponse();
        if (!context.isLifecycleUnchanged()) return ApiResponse.error(ApiStatus.SERVER_STOPPING);
        if (subscription == null) return response;
        ApiStatus failure = null;
        if (context.getSession() == null || !context.getSession()
            .isValid(System.currentTimeMillis())) failure = ApiStatus.UNAUTHORIZED;
        else if (CoreEngine.GRID_IDENTITIES.getGrid(gridKey) != grid) failure = ApiStatus.GRID_NOT_FOUND;
        else if (!GridAccess.allows(grid, context.getPrincipal())) failure = ApiStatus.NO_PERMISSIONS;
        if (failure == null) return response;
        cancel(subscription);
        return ApiResponse.error(failure);
    }
}
