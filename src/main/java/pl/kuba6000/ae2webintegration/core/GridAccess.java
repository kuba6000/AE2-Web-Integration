package pl.kuba6000.ae2webintegration.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Immutable snapshot of the grids a single web user may access, plus the moment it was computed.
 * <p>
 * Async request handlers run off the Minecraft server thread and therefore cannot read live AE2 security
 * state. Instead, the server thread computes this set during a synced request and the async handlers read
 * it. The cost of that trade-off is bounded staleness, expressed by {@link #TTL_MILLIS}.
 */
public final class GridAccess {

    /** How long a computed access set stays usable before an async request must refuse to trust it. */
    public static final long TTL_MILLIS = 5L * 60L * 1000L;
    public static final int UNRESOLVED_PLAYER_ID = -1;

    private final int playerId;
    private final Set<StableKey> accessibleGridKeys;
    private final long computedAtMillis;

    @SuppressWarnings("Java9CollectionFactory") // Set.copyOf is unavailable on Java 8.
    public GridAccess(int playerId, Set<StableKey> accessibleGridKeys, long computedAtMillis) {
        this.playerId = playerId;
        this.accessibleGridKeys = Collections.unmodifiableSet(new HashSet<>(accessibleGridKeys));
        this.computedAtMillis = computedAtMillis;
    }

    public int getPlayerId() {
        return playerId;
    }

    public boolean hasResolvedPlayerId() {
        return playerId >= 0;
    }

    public boolean canAccess(StableKey gridKey) {
        return accessibleGridKeys.contains(gridKey);
    }

    /** Past this point the set must not be trusted; the caller should ask the client to refresh. */
    public boolean isStale(long nowMillis) {
        return nowMillis - computedAtMillis >= TTL_MILLIS;
    }

    public Set<StableKey> getAccessibleGridKeys() {
        return accessibleGridKeys;
    }

}
