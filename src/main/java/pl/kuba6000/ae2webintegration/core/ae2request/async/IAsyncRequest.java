package pl.kuba6000.ae2webintegration.core.ae2request.async;

import java.util.Map;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Requests served directly on the HTTP worker thread, without a hop through the server tick.
 * <p>
 * Reads stored GridData and checks UUID access through the grid's thread-safe permission registry.
 * Reverse identity lookup and authorization never inspect native AE2 state on the HTTP worker.
 */
public abstract class IAsyncRequest extends IRequest {

    protected StableKey gridKey;
    protected GridData grid = null;

    public void handle(Map<String, String> getParams) {}

    public void handle(AE2Controller.RequestContext context) {
        String gridstr = context.getGetParams()
            .get("grid");
        if (gridstr == null || gridstr.isEmpty()) {
            gridKey = null;
        } else {
            try {
                gridKey = StableKey.parse(gridstr);
            } catch (IllegalArgumentException e) {
                deny("BAD_PARAM");
                return;
            }
        }
        if (gridKey != null) {
            if (!GridAccess.allows(CoreEngine.GRID_IDENTITIES.getGrid(gridKey), context.getPrincipal())) {
                deny("NO_PERMISSIONS");
                return;
            }
            // Lookup, not create: a handler that stores something asks for the entry itself, so a plain
            // read does not allocate runtime state for a grid.
            grid = GridData.find(gridKey);
        }
        handle(context.getGetParams());
    }
}
