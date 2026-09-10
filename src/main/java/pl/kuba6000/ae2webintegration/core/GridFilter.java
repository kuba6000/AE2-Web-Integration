package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;

/**
 * Shared "is this grid usable by the web interface" predicate.
 * <p>
 * The same online + security check used to be spelled out separately in {@link GridData#getOrCreate(IAEGrid)},
 * {@code ISyncedRequest#handle(IAE)} and {@code GetGridList#handle(IAE)}. Keeping a single copy stops the
 * three from drifting apart.
 */
public final class GridFilter {

    private GridFilter() {}

    /**
     * @return the grid's security service when the network is online and its security is available,
     *         otherwise {@code null}. Callers that need an attachable grid must additionally reject a
     *         security key of {@code -1}.
     */
    public static IAESecurityGrid usableSecurity(IAEGrid grid) {
        if (grid == null) {
            return null;
        }
        IAEPathingGrid pathing = grid.web$getPathingGrid();
        if (pathing == null || pathing.web$isNetworkBooting()
            || pathing.web$getControllerState() != AEControllerState.CONTROLLER_ONLINE) {
            return null;
        }
        IAESecurityGrid security = grid.web$getSecurityGrid();
        if (security == null || !security.web$isAvailable()) {
            return null;
        }
        return security;
    }
}
