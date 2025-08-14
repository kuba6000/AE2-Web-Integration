package com.kuba6000.ae2webintegration.core;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import com.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import com.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class GridData {

    @GSONUtils.SkipGSON
    private static final ConcurrentHashMap<Long, GridData> gridDataMap = new ConcurrentHashMap<>();

    public boolean isTracked = false;

    @GSONUtils.SkipGSON
    public AE2JobTracker trackingInfo = new AE2JobTracker();

    @GSONUtils.SkipGSON
    private int nextJobID = 1;

    private int getNextJobID() {
        return nextJobID++;
    }

    @GSONUtils.SkipGSON
    public HashMap<Integer, Future<IAECraftingJob>> jobs = new HashMap<>();

    public int addJob(Future<IAECraftingJob> job) {
        int jobID = getNextJobID();
        jobs.put(jobID, job);
        return jobID;
    }

    public static GridData get(long gridKey) {
        return gridDataMap.computeIfAbsent(gridKey, k -> new GridData());
    }

    public static GridData get(IAEGrid grid) {
        IAEPathingGrid pathing = grid.web$getPathingGrid();
        if (pathing == null || pathing.web$isNetworkBooting()
            || pathing.web$getControllerState() != AEControllerState.CONTROLLER_ONLINE) {
            return null;
        }
        IAESecurityGrid security = grid.web$getSecurityGrid();
        if (security == null || !security.web$isAvailable()) {
            return null;
        }
        long gridKey = security.web$getSecurityKey();
        if (gridKey == -1) {
            return null;
        }
        return gridDataMap.computeIfAbsent(gridKey, k -> new GridData());
    }
}
