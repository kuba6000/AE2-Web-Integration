package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.List;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.api.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

public class GetGridList extends ISyncedRequest {

    @Desugar
    private record JSON_GridData(StableKey key, int cpuCount, String owner, boolean isOwned, boolean isTrackingEnabled,
        List<GridAccessSource> accessSources) {

        JSON_GridData(GridAccessSessions.View view, boolean isOwned) {
            this(
                view.key(),
                view.grid()
                    .web$getCraftingGrid()
                    .web$getCPUCount(),
                view.owner() == null ? "N/A" : view.owner().name,
                isOwned,
                CoreEngine.GRID_IDENTITIES.isTracked(view.key()),
                view.sources());
        }
    }

    @Override
    public void handle(IAE ae) {
        ArrayList<JSON_GridData> result = new ArrayList<>();
        for (GridAccessSessions.View view : grids) {
            if (!view.allows(context.getPrincipal())) continue;
            result.add(new JSON_GridData(view, !context.isAdmin()));
        }
        result.sort((first, second) -> {
            int owned = Boolean.compare(second.isOwned(), first.isOwned());
            if (owned != 0) return owned;
            int tracked = Boolean.compare(second.isTrackingEnabled(), first.isTrackingEnabled());
            return tracked != 0 ? tracked : Integer.compare(second.cpuCount(), first.cpuCount());
        });
        succeed(result);
    }
}
