package pl.kuba6000.ae2webintegration.core;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;

/**
 * Per-user cache of accessible grid keys, written by the server thread and read by HTTP worker threads.
 * <p>
 * Keyed by stable web principal rather than by session token or world-scoped AE2 player id on purpose:
 * authorization is a property of the user, while both tokens and AE2 ids may change independently.
 * <p>
 * Fail-closed: a user with no cached entry is refused, so async endpoints deny by default until a synced
 * request has established what that user may see.
 */
public final class GridAccessSessions {

    private GridAccessSessions() {}

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final ConcurrentHashMap<WebPrincipal, GridAccess> sessions = new ConcurrentHashMap<>();

    public static GridAccess get(WebPrincipal principal) {
        return sessions.get(principal);
    }

    public static void put(WebPrincipal principal, GridAccess access) {
        sessions.put(principal, access);
    }

    /** Called on explicit logout - a new login recomputes from scratch. */
    public static void invalidate(WebPrincipal principal) {
        sessions.remove(principal);
    }

    /** Called on server stop so authorization never carries across a singleplayer world reload. */
    public static void clear() {
        sessions.clear();
    }

    /**
     * Recomputes which grids {@code principal} may access and resolves the current world's AE2 player id.
     * <p>
     * An admin is not permission-checked, so their set is every attachable grid and the check reduces to
     * "does this grid exist" - which is what the synced path has always required of admins too. Without
     * it an admin could name any number at all and have a phantom entry written to griddata.json.
     * <p>
     * MUST run on the Minecraft server thread - it reads live AE2 security state.
     */
    public static GridAccess compute(IAE ae, WebPrincipal principal, long nowMillis) {
        int playerId = GridAccess.UNRESOLVED_PLAYER_ID;
        if (!principal.isAdmin()) {
            try {
                playerId = ae.web$getPlayerData()
                    .web$getPlayerId(principal.getPlayerIdentity());
            } catch (Exception e) {
                LOG.error("Failed to resolve the AE2 player ID for web user " + principal.getUsername(), e);
            }
            if (playerId < 0) {
                return new GridAccess(GridAccess.UNRESOLVED_PLAYER_ID, new HashSet<>(), nowMillis);
            }
        }

        Set<Long> keys = new HashSet<>();
        for (IAEGrid grid : ae.web$getGrids()) {
            IAESecurityGrid security = GridFilter.usableSecurity(grid);
            if (security == null) {
                continue;
            }
            long gridKey = security.web$getSecurityKey();
            if (gridKey == -1) {
                continue;
            }
            if (principal.isAdmin() || security.web$hasPermissions(playerId)) {
                keys.add(gridKey);
            }
        }
        return new GridAccess(playerId, keys, nowMillis);
    }

    /** Recomputes and publishes the user's access from the live AE2 state on the server thread. */
    public static GridAccess refresh(IAE ae, WebPrincipal principal, long nowMillis) {
        GridAccess current = compute(ae, principal, nowMillis);
        sessions.put(principal, current);
        return current;
    }
}
