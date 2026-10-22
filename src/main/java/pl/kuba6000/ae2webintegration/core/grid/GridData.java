package pl.kuba6000.ae2webintegration.core.grid;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

public class GridData {

    private static final ConcurrentHashMap<StableKey, GridData> gridDataMap = new ConcurrentHashMap<>();

    private static Iterator<GridData> craftingPlanMaintenanceCursor;

    private final CraftingPlanRegistry craftingPlans = new CraftingPlanRegistry(System::nanoTime);

    public int addJob(@NotNull Future<IAECraftingJob> job, @NotNull ResourceSnapshot finalOutput,
        @NotNull CraftingOptions options, boolean autoStart) {
        return craftingPlans.add(job, finalOutput, options, autoStart);
    }

    public @Nullable CraftingPlanRegistry.Entry getJob(int jobId) {
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
        }
        gridDataMap.clear();
        craftingPlanMaintenanceCursor = null;
    }

    public static synchronized boolean evictExpiredCompletedPlans(long nowNanos, int maxGrids) {
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

    public static void retire(StableKey gridKey) {
        GridData data = gridDataMap.remove(gridKey);
        if (data == null) return;
        data.craftingPlans.clearForServerStop();
    }

    public static GridData getOrCreate(StableKey gridKey) {
        return gridDataMap.computeIfAbsent(gridKey, key -> new GridData());
    }
}
