package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.IServerThreadTask;
import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;

public abstract class ISyncedRequest extends IRequest implements IServerThreadTask {

    protected AE2Controller.RequestContext context = null;
    protected StableKey gridKey;
    protected List<GridAccessSessions.View> grids = Collections.emptyList();
    protected IAEGrid grid = null;
    protected GridData gridData = null;
    protected GridAccess access = null;

    boolean init(Map<String, String> getParams) {
        return true;
    }

    public boolean init(AE2Controller.RequestContext context) {
        this.context = context;
        String gridstr = context.getGetParams()
            .get("grid");
        if (gridstr == null || gridstr.isEmpty()) {
            gridKey = null;
        } else {
            try {
                gridKey = StableKey.parse(gridstr);
            } catch (IllegalArgumentException e) {
                deny("BAD_PARAM");
                return false;
            }
        }
        return init(context.getGetParams());
    }

    void handle(IAEGrid grid) {}

    public void handle(IAE ae) {
        if (gridKey != null) {
            for (GridAccessSessions.View candidate : grids) {
                if (!gridKey.equals(candidate.key())) continue;
                if (!candidate.allows(context.getPrincipal())) {
                    deny("NO_PERMISSIONS");
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
                grids = GridAccessSessions.snapshot(ae);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to prepare grid identities", e);
            }
            access = GridAccessSessions.compute(ae, context.getPrincipal(), System.currentTimeMillis(), grids);
            GridAccessSessions.put(context.getPrincipal(), access);
        }
        handle(ae);
    }

}
