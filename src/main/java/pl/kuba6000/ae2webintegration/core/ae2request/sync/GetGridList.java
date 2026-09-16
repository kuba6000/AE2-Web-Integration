package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;

import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.Gson;
import com.google.gson.JsonArray;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

public class GetGridList extends ISyncedRequest {

    @Desugar
    private record JSON_GridData(StableKey key, int cpuCount, String owner, boolean isOwned, boolean isTrackingEnabled,
        JsonArray accessSources) {

        JSON_GridData(GridAccess.View view, boolean isOwned, @Nullable PlayerIdentity owner, JsonArray sources) {
            this(
                view.key(),
                view.grid()
                    .web$getCraftingGrid()
                    .web$getCPUCount(),
                owner == null ? "N/A" : owner.name,
                isOwned,
                CoreEngine.GRID_IDENTITIES.isTracked(view.key()),
                sources);
        }
    }

    @Override
    public void handle() {
        ArrayList<JSON_GridData> result = new ArrayList<>();
        Gson gson = JSONBuilder.create();
        for (GridAccess.View view : grids) {
            if (!view.allows(context.getPrincipal())) continue;
            JsonArray sources = new JsonArray();
            view.grid()
                .web$getPermissions()
                .values()
                .forEach(entries -> entries.forEach(source -> sources.add(gson.toJsonTree(source))));
            result.add(
                new JSON_GridData(
                    view,
                    !context.isAdmin(),
                    view.grid()
                        .web$getRepresentativeOwner(),
                    sources));
        }
        result.sort((first, second) -> {
            int owned = Boolean.compare(second.isOwned(), first.isOwned());
            if (owned != 0) return owned;
            int tracked = Boolean.compare(second.isTrackingEnabled(), first.isTrackingEnabled());
            return tracked != 0 ? tracked : Integer.compare(second.cpuCount(), first.cpuCount());
        });
        // Permission sources are already detached JSON; no live map or lists reach the HTTP worker.
        succeed(result);
    }
}
