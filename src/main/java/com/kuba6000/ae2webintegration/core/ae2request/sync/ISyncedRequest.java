package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.Map;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.GridData;
import com.kuba6000.ae2webintegration.core.ae2request.IRequest;
import com.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import com.kuba6000.ae2webintegration.core.interfaces.IAE;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;

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
        if (gridstr == null || gridstr.isEmpty()) gridKey = -1;
        else gridKey = Long.parseLong(gridstr);
        return init(context.getGetParams());
    }

    void handle(IAEGrid grid) {}

    public void handle(IAE ae) {
        if (gridKey != -1) {
            for (IAEGrid grid : ae.web$getGrids()) {
                IAEPathingGrid pathing = grid.web$getPathingGrid();
                if (pathing == null || pathing.web$isNetworkBooting()
                    || pathing.web$getControllerState() != AEControllerState.CONTROLLER_ONLINE) {
                    continue;
                }
                IAESecurityGrid security = grid.web$getSecurityGrid();
                if (security == null || !security.web$isAvailable()) {
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
        if (grid != null) gridData = GridData.get(gridKey);
        handle(grid);
    }

    @Override
    public void handle(AE2Controller.RequestContext context) {
        throw new IllegalArgumentException("ONLY SYNCED");
    }
}
