package pl.kuba6000.ae2webintegration.core.ae2request.async;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;

/** HTTP-thread operations read stored data and synchronized permissions, never live native game state. */
public abstract class IAsyncRequest extends IRequest {

    @PathParam("gridKey")
    protected StableKey gridKey;
    protected GridData grid;

    @Override
    public boolean init(RequestContext context) {
        if (!super.init(context)) return false;
        if (gridKey != null) {
            IAEGrid liveGrid = CoreEngine.GRID_IDENTITIES.getGrid(gridKey);
            if (liveGrid == null) {
                deny(ApiStatus.GRID_NOT_FOUND);
                return false;
            }
            if (!GridAccess.allows(liveGrid, context.getPrincipal())) {
                deny(ApiStatus.NO_PERMISSIONS);
                return false;
            }
            grid = GridData.find(gridKey);
        }
        return true;
    }

    public abstract void handle();

    public final void handle(RequestContext context) {
        if (init(context)) handle();
    }
}
