package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;

import com.kuba6000.ae2webintegration.core.GridData;
import com.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import com.kuba6000.ae2webintegration.core.interfaces.IAE;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import com.mojang.authlib.GameProfile;

public class GetGridList extends ISyncedRequest {

    private static class JSON_GridData {

        JSON_GridData(long key, int cpuCount, String owner, boolean isOwned, boolean isTrackingEnabled) {
            this.key = key;
            this.cpuCount = cpuCount;
            this.owner = owner;
            this.isOwned = isOwned;
            this.isTrackingEnabled = isTrackingEnabled;
        }

        public long key; // key == -1 -> not attachable
        public int cpuCount;
        public String owner;
        public boolean isOwned;
        public boolean isTrackingEnabled = false;
    }

    @Override
    public void handle(IAE ae) {
        ArrayList<JSON_GridData> grids = new ArrayList<>();
        for (IAEGrid grid : ae.web$getGrids()) {
            IAEPathingGrid pathing = grid.web$getPathingGrid();
            if (pathing == null || pathing.web$isNetworkBooting()
                || pathing.web$getControllerState() != AEControllerState.CONTROLLER_ONLINE) {
                continue;
            }
            IAESecurityGrid security = grid.web$getSecurityGrid();
            if (security == null || !security.web$isAvailable() || security.web$getSecurityKey() == -1) {
                if (context.isAdmin()) {
                    grids.add(
                        new JSON_GridData(
                            -1,
                            grid.web$getCraftingGrid()
                                .web$getCPUCount(),
                            "N/A",
                            false,
                            false));
                }
                continue;
            }
            if (!context.isAdmin() && !security.web$hasPermissions(context.getUserID())) {
                continue;
            }
            GameProfile gameProfile = security.web$getOwnerProfile();
            GridData gridData = GridData.get(security.web$getSecurityKey());
            grids.add(
                new JSON_GridData(
                    security.web$getSecurityKey(),
                    grid.web$getCraftingGrid()
                        .web$getCPUCount(),
                    gameProfile == null ? "N/A" : gameProfile.getName(),
                    security.web$hasPermissions(context.getUserID()),
                    gridData.isTracked));
        }
        setData(grids);
        done();
    }
}
