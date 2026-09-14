package pl.kuba6000.ae2webintegration.core;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

public class GridData {

    private static final ConcurrentHashMap<StableKey, GridData> gridDataMap = new ConcurrentHashMap<>();

    private static Iterator<GridData> craftingPlanMaintenanceCursor;

    public AE2JobTracker trackingInfo = new AE2JobTracker();

    private final CraftingPlanRegistry craftingPlans = new CraftingPlanRegistry(System::nanoTime);

    public int addJob(Future<IAECraftingJob> job) {
        return craftingPlans.add(job);
    }

    public Future<IAECraftingJob> getJob(int jobId) {
        return craftingPlans.find(jobId);
    }

    public void removeJob(int jobId) {
        craftingPlans.remove(jobId);
    }

    public void cancelJob(int jobId) {
        craftingPlans.cancel(jobId);
    }

    public static synchronized void clearRuntimeState() {
        for (GridData gridData : gridDataMap.values()) {
            gridData.craftingPlans.clearForServerStop();
            gridData.trackingInfo.clearHistory();
        }
        gridDataMap.clear();
        craftingPlanMaintenanceCursor = null;
    }

    static synchronized boolean evictExpiredCompletedPlans(long nowNanos, int maxGrids) {
        if (craftingPlanMaintenanceCursor == null) {
            craftingPlanMaintenanceCursor = gridDataMap.values()
                .iterator();
        }

        int processed = 0;
        while (processed < maxGrids && craftingPlanMaintenanceCursor.hasNext()) {
            craftingPlanMaintenanceCursor.next().craftingPlans.evictExpiredCompleted(nowNanos);
            processed++;
        }

        if (craftingPlanMaintenanceCursor.hasNext()) {
            return false;
        }
        craftingPlanMaintenanceCursor = null;
        return true;
    }

    /** Runtime state is allocated only for an operation that needs it, never for listing. */
    public static GridData find(StableKey gridKey) {
        return gridDataMap.get(gridKey);
    }

    static void retire(StableKey gridKey) {
        GridData data = gridDataMap.remove(gridKey);
        if (data == null) return;
        data.craftingPlans.clearForServerStop();
        data.trackingInfo.clearHistory();
    }

    public static GridData getOrCreate(StableKey gridKey) {
        return gridDataMap.computeIfAbsent(gridKey, key -> new GridData());
    }
}
