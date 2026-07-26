package pl.kuba6000.ae2webintegration.core.ae2request.async;

import java.util.Map;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.GridAccess;
import pl.kuba6000.ae2webintegration.core.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.GridData;
import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;

/**
 * Requests served directly on the HTTP worker thread, without a hop through the server tick.
 * <p>
 * They only ever touch stored {@link GridData}, never live AE2 state, which is what keeps them off the
 * server thread. Authorization therefore cannot be evaluated here - it is read from
 * {@link GridAccessSessions}, which the server thread keeps up to date during synced requests.
 */
public abstract class IAsyncRequest extends IRequest {

    protected AE2Controller.RequestContext context = null;
    protected long gridKey = -1;
    protected GridData grid = null;

    public void handle(Map<String, String> getParams) {};

    @Override
    public void handle(AE2Controller.RequestContext context) {
        this.context = context;
        String gridstr = context.getGetParams()
            .get("grid");
        if (gridstr == null || gridstr.isEmpty()) {
            gridKey = -1;
        } else {
            try {
                gridKey = Long.parseLong(gridstr);
            } catch (NumberFormatException e) {
                deny("BAD_PARAM");
                return;
            }
        }
        if (gridKey != -1) {
            if (!context.isAdmin()) {
                GridAccess access = GridAccessSessions.get(context.getUserID());
                if (access == null || access.isStale(System.currentTimeMillis())) {
                    // Distinct from NO_PERMISSIONS so the client can re-fetch the grid list and retry
                    // instead of reporting a permission error.
                    deny("REFRESH_REQUIRED");
                    return;
                }
                if (!access.canAccess(gridKey)) {
                    deny("NO_PERMISSIONS");
                    return;
                }
            }
            grid = GridData.get(gridKey);
        }
        handle(context.getGetParams());
    }
}
