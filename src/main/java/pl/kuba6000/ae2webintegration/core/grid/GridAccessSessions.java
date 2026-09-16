package pl.kuba6000.ae2webintegration.core.grid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;

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

    /** Invalidates cached grants immediately; the next synced request reads current permission sources. */
    public static void permissionsChanged() {
        sessions.clear();
    }

    /**
     * Recomputes which grids {@code principal} may access.
     * <p>
     * An admin is not permission-checked, so their set is every attachable grid and the check reduces to
     * "does this grid exist" - which is what the synced path has always required of admins too. Without
     * it an admin could supply an unknown key and create phantom runtime state.
     * <p>
     * MUST run on the Minecraft server thread - it reads current native grid membership and access sources.
     */
    public static GridAccess compute(IAE ae, WebPrincipal principal, long nowMillis) {
        try {
            return compute(principal, nowMillis, snapshot(ae));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare grid identities", e);
        }
    }

    public static GridAccess compute(WebPrincipal principal, long nowMillis, List<View> grids) {
        Set<StableKey> keys = new HashSet<>();
        for (View grid : grids) {
            if (grid.allows(principal)) keys.add(grid.key());
        }
        return new GridAccess(keys, nowMillis);
    }

    /** Recomputes and publishes the user's access from the live AE2 state on the server thread. */
    public static GridAccess refresh(IAE ae, WebPrincipal principal, long nowMillis) {
        GridAccess current = compute(ae, principal, nowMillis);
        sessions.put(principal, current);
        return current;
    }

    /** Captures access facts for grids already identified by their controller-validation callbacks. */
    public static @NotNull List<View> snapshot(@NotNull IAE ae) throws IOException {
        GridIdentityRegistry registry = CoreEngine.GRID_IDENTITIES;
        synchronized (registry) {
            if (!registry.isInitialized()) throw new IOException("Grid identities are not initialized for this save");
            List<View> result = new ArrayList<>();
            for (IAEGrid grid : ae.web$getGrids()) {
                StableKey key = registry.getKey(grid);
                if (key != null && GridFilter.isUsable(grid)) {
                    result.add(new View(grid, key, grid.web$getAccessSources(), grid.web$getRepresentativeOwner()));
                }
            }
            return result;
        }
    }

    /** Request-owned native reference plus detached access facts, never stored in async sessions. */
    @Desugar
    public record View(@NotNull IAEGrid grid, @NotNull StableKey key, @NotNull List<GridAccessSource> sources,
        @Nullable PlayerIdentity owner) {

        // Jabel changes syntax support; List.copyOf is unavailable on the Java 8 runtime.
        @SuppressWarnings("Java9CollectionFactory")
        public View {
            sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }

        public boolean allows(@NotNull WebPrincipal principal) {
            if (principal.isAdmin()) return true;
            PlayerIdentity player = principal.getPlayerIdentity();
            if (player == null) return false;
            for (GridAccessSource source : sources) if (source.allows(player.uuid)) return true;
            return false;
        }
    }
}
