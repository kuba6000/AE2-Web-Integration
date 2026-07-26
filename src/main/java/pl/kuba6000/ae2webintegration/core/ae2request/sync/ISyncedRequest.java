package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.Map;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.GridData;
import pl.kuba6000.ae2webintegration.core.GridFilter;
import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;

public abstract class ISyncedRequest extends IRequest {

    protected AE2Controller.RequestContext context = null;
    protected long gridKey = -1;
    protected IAEGrid grid = null;
    protected GridData gridData = null;

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
        // We are on the server thread with live AE2 state in hand, so this is the cheapest place to keep
        // the async endpoints' authorization data fresh. Refreshing at half life rather than on expiry
        // means an active session never sees REFRESH_REQUIRED.
        if (context != null) {
            GridAccessSessions
                .refreshIfHalfLifeElapsed(ae, context.getUserID(), context.isAdmin(), System.currentTimeMillis());
        }
        if (gridKey != -1) {
            for (IAEGrid grid : ae.web$getGrids()) {
                IAESecurityGrid security = GridFilter.usableSecurity(grid);
                if (security == null) {
                    continue;
                }
                if (gridKey == security.web$getSecurityKey()) {
                    if (!context.isAdmin() && !security.web$hasPermissions(context.getUserID())) {
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
    public void handle(AE2Controller.RequestContext context) {
        throw new IllegalArgumentException("ONLY SYNCED");
    }
}
