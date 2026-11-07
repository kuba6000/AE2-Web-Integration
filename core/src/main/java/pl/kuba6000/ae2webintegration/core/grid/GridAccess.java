package pl.kuba6000.ae2webintegration.core.grid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

/** Access checks read the grid's live permission registry, never a per-user grant cache. */
public final class GridAccess {

    private GridAccess() {}

    public static boolean allows(@Nullable IAEGrid grid, @NotNull WebPrincipal principal) {
        if (grid == null) return false;
        if (principal.isAdmin()) return true;
        PlayerIdentity player = principal.getPlayerIdentity();
        return player != null && grid.web$hasAccess(player.uuid);
    }

    /** Server thread only: resolves currently usable grids without reading their permission maps. */
    public static @NotNull List<View> list(@NotNull IAE ae) throws IOException {
        GridIdentityRegistry registry = CoreEngine.GRID_IDENTITIES;
        synchronized (registry) {
            if (!registry.isInitialized()) throw new IOException("Grid identities are not initialized for this save");
            List<View> result = new ArrayList<>();
            for (IAEGrid grid : ae.web$getGrids()) {
                StableKey key = registry.getKey(grid);
                if (key != null && GridFilter.isUsable(grid)) result.add(new View(grid, key));
            }
            return result;
        }
    }

    /** Used only while executing a request on the server thread. */
    @Desugar
    public record View(@NotNull IAEGrid grid, @NotNull StableKey key) {

        public boolean allows(@NotNull WebPrincipal principal) {
            return GridAccess.allows(grid, principal);
        }
    }
}
