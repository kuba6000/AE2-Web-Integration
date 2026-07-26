package pl.kuba6000.ae2webintegration.core;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;

/**
 * Per-user cache of accessible grid keys, written by the server thread and read by HTTP worker threads.
 * <p>
 * Keyed by web user id rather than by session token on purpose: authorization is a property of the user,
 * not of a browser tab, and authentication tokens rotate independently.
 * <p>
 * Fail-closed: a user with no cached entry is refused, so async endpoints deny by default until a synced
 * request has established what that user may see.
 */
public final class GridAccessSessions {

    private GridAccessSessions() {}

    private static final ConcurrentHashMap<Integer, GridAccess> sessions = new ConcurrentHashMap<>();

    public static GridAccess get(int userId) {
        return sessions.get(userId);
    }

    public static void put(int userId, GridAccess access) {
        sessions.put(userId, access);
    }

    /** Called on explicit logout - a new login recomputes from scratch. */
    public static void invalidate(int userId) {
        sessions.remove(userId);
    }

    /** Called on server stop so authorization never carries across a singleplayer world reload. */
    public static void clear() {
        sessions.clear();
    }

    /**
     * Recomputes which grids {@code userId} may access.
     * <p>
     * An admin is not permission-checked, so their set is every attachable grid and the check reduces to
     * "does this grid exist" - which is what the synced path has always required of admins too. Without
     * it an admin could name any number at all and have a phantom entry written to griddata.json.
     * <p>
     * MUST run on the Minecraft server thread - it reads live AE2 security state.
     */
    public static GridAccess compute(IAE ae, int userId, boolean isAdmin, long nowMillis) {
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
            if (isAdmin || security.web$hasPermissions(userId)) {
                keys.add(gridKey);
            }
        }
        return new GridAccess(keys, nowMillis);
    }

    /**
     * Refreshes the user's access set when it is missing or past half its lifetime. Cheap on the common
     * path: a single map lookup and a timestamp comparison.
     */
    public static void refreshIfHalfLifeElapsed(IAE ae, int userId, boolean isAdmin, long nowMillis) {
        GridAccess current = sessions.get(userId);
        if (current == null || current.isHalfLifeElapsed(nowMillis)) {
            sessions.put(userId, compute(ae, userId, isAdmin, nowMillis));
        }
    }
}
