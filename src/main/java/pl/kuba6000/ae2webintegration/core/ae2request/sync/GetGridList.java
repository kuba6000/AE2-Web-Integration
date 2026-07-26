package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import pl.kuba6000.ae2webintegration.core.GridAccess;
import pl.kuba6000.ae2webintegration.core.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.GridData;
import pl.kuba6000.ae2webintegration.core.GridFilter;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;

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
        // This loop computes exactly the set the async endpoints need, so publish it while we are here.
        Set<Long> accessibleKeys = new HashSet<>();
        for (IAEGrid grid : ae.web$getGrids()) {
            IAESecurityGrid security = GridFilter.usableSecurity(grid);
            if (security == null || security.web$getSecurityKey() == -1) {
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
            accessibleKeys.add(security.web$getSecurityKey());
            PlayerIdentity ownerIdentity = security.web$getOwnerProfile();
            GridData gridData = GridData.get(security.web$getSecurityKey());
            grids.add(
                new JSON_GridData(
                    security.web$getSecurityKey(),
                    grid.web$getCraftingGrid()
                        .web$getCPUCount(),
                    ownerIdentity == null ? "N/A" : ownerIdentity.name,
                    security.web$hasPermissions(context.getUserID()),
                    gridData.isTracked));
        }
        grids.sort((d1, d2) -> {
            if (d1.isOwned && !d2.isOwned) {
                return -1;
            } else if (!d1.isOwned && d2.isOwned) {
                return 1;
            } else if (d1.isTrackingEnabled && !d2.isTrackingEnabled) {
                return -1;
            } else if (!d1.isTrackingEnabled && d2.isTrackingEnabled) {
                return 1;
            } else if (d1.key == -1 && d2.key != -1) {
                return 1; // unattached grids go to the end
            } else if (d1.key != -1 && d2.key == -1) {
                return -1; // attached grids come first
            } else {
                return Integer.compare(d2.cpuCount, d1.cpuCount); // sort by cpu count if all else is equal
            }
        });
        if (!context.isAdmin()) {
            GridAccessSessions.put(context.getUserID(), new GridAccess(accessibleKeys, System.currentTimeMillis()));
        }
        setData(grids);
        done();
    }
}
