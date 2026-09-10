package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.Map;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.GridAccess;
import pl.kuba6000.ae2webintegration.core.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.GridData;
import pl.kuba6000.ae2webintegration.core.GridFilter;
import pl.kuba6000.ae2webintegration.core.IServerThreadTask;
import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;

public abstract class ISyncedRequest extends IRequest implements IServerThreadTask {

    protected AE2Controller.RequestContext context = null;
    protected long gridKey = -1;
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
            gridKey = -1;
        } else {
            Long parsed = HTTPUtils.parseLong(gridstr);
            if (parsed == null) {
                deny("BAD_PARAM");
                return false;
            }
            gridKey = parsed;
        }
        return init(context.getGetParams());
    }

    void handle(IAEGrid grid) {}

    public void handle(IAE ae) {
        if (gridKey != -1) {
            if (!context.isAdmin() && (access == null || !access.hasResolvedPlayerId())) {
                deny("NO_PERMISSIONS");
                return;
            }
            for (IAEGrid grid : ae.web$getGrids()) {
                IAESecurityGrid security = GridFilter.usableSecurity(grid);
                if (security == null) {
                    continue;
                }
                if (gridKey == security.web$getSecurityKey()) {
                    if (!context.isAdmin() && !security.web$hasPermissions(access.getPlayerId())) {
                        deny("NO_PERMISSIONS");
                        return;
                    }
                    this.grid = grid;
                }
            }
        }
        if (grid != null) gridData = GridData.getOrCreate(gridKey);
        handle(grid);
    }

    @Override
    public final void runOnServerThread(IAE ae) {
        // Every synced request already runs with live AE2 state on the server thread. Publish that state
        // before dynamic dispatch so handlers overriding handle(IAE), such as GetGridList, cannot retain a
        // stale grid-access snapshot after a security terminal or biometric card changes.
        if (context != null) {
            access = GridAccessSessions.refresh(ae, context.getPrincipal(), System.currentTimeMillis());
        }
        handle(ae);
    }

}
