package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

public class GetGridList extends ISyncedRequest {

    @Desugar
    private record JSON_GridData(StableKey key, int cpuCount, String owner, boolean isOwned, boolean isTrackingEnabled,
        Map<UUID, List<GridAccessSource>> accessSources) {

        JSON_GridData(GridAccess.View view, boolean isOwned, @Nullable PlayerIdentity owner,
            Map<UUID, List<GridAccessSource>> sources, GridSettingsData settings) {
            this(
                view.key(),
                view.grid()
                    .web$getCraftingGrid()
                    .web$getCPUCount(),
                owner == null ? "N/A" : owner.name,
                isOwned,
                settings.isTracked(),
                sources);
        }
    }

    @Override
    public void handle() {
        ArrayList<JSON_GridData> result = new ArrayList<>();
        for (GridAccess.View view : grids) {
            if (!view.allows(context.getPrincipal())) continue;
            var data = CoreEngine.GRID_IDENTITIES.getPersistentData(view.key());
            if (data == null) continue;
            result.add(
                new JSON_GridData(
                    view,
                    !context.isAdmin(),
                    view.grid()
                        .web$getRepresentativeOwner(),
                    view.grid()
                        .web$getPermissions(),
                    data.getSettings()));
        }
        result.sort((first, second) -> {
            int owned = Boolean.compare(second.isOwned(), first.isOwned());
            if (owned != 0) return owned;
            int tracked = Boolean.compare(second.isTrackingEnabled(), first.isTrackingEnabled());
            return tracked != 0 ? tracked : Integer.compare(second.cpuCount(), first.cpuCount());
        });
        // succeed serializes these live collections on the server thread before completing the request.
        succeed(result);
    }
}
