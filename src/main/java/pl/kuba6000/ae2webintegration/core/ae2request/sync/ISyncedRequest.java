package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import pl.kuba6000.ae2webintegration.core.IServerThreadTask;
import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;

public abstract class ISyncedRequest extends IRequest implements IServerThreadTask {

    @PathParam("gridKey")
    protected StableKey gridKey;
    protected List<GridAccess.View> grids = Collections.emptyList();
    protected IAEGrid grid = null;
    protected GridData gridData = null;

    protected void handle(IAEGrid grid) {}

    public void handle() {
        if (gridKey != null) {
            for (GridAccess.View candidate : grids) {
                if (!gridKey.equals(candidate.key())) continue;
                if (!candidate.allows(context.getPrincipal())) {
                    deny(ApiStatus.NO_PERMISSIONS);
                    return;
                }
                this.grid = candidate.grid();
                break;
            }
        }
        if (grid != null) gridData = GridData.getOrCreate(gridKey);
        handle(grid);
    }

    @Override
    public final void runOnServerThread(IAE ae) {
        if (context != null) {
            try {
                grids = GridAccess.list(ae);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to prepare grid identities", e);
            }
        }
        handle();
    }

}
