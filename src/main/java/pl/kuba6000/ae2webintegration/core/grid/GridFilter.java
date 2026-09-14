package pl.kuba6000.ae2webintegration.core.grid;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;

/** Shared availability check; access is resolved independently from current sources. */
public final class GridFilter {

    private GridFilter() {}

    public static boolean isUsable(IAEGrid grid) {
        if (grid == null) return false;
        IAEPathingGrid pathing = grid.web$getPathingGrid();
        return pathing != null && !pathing.web$isNetworkBooting()
            && pathing.web$getControllerState() == AEControllerState.CONTROLLER_ONLINE;
    }
}
