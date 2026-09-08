package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.JSON_Stack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

public class GetCPUList extends ISyncedRequest {

    private static class JSON_CpuInfo {

        public String name;
        public boolean isBusy;
        public JSON_Stack finalOutput;
        public long availableStorage;
        public long usedStorage;
        public long coProcessors;
        public boolean hasTrackingInfo = false;
        public long timeStarted = 0L;
    }

    /** Returns null if native CPUs expose ambiguous addresses. */
    @Nullable
    public static Map<String, ICraftingCPUCluster> getCPUList(IAECraftingGrid craftingGrid) {
        LinkedHashMap<String, ICraftingCPUCluster> orderedMap = new LinkedHashMap<>();
        for (ICraftingCPUCluster cpu : craftingGrid.web$getCPUs()) {
            if (orderedMap.put(cpu.web$getId(), cpu) != null) return null;
        }
        return orderedMap;
    }

    @Override
    boolean init(Map<String, String> getParams) {
        return true;
    }

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        Map<String, ICraftingCPUCluster> clusters = getCPUList(grid.web$getCraftingGrid());
        if (clusters == null) {
            deny("CPU_ID_CONFLICT");
            return;
        }
        LinkedHashMap<String, JSON_CpuInfo> cpuList = new LinkedHashMap<>(clusters.size());
        for (Map.Entry<String, ICraftingCPUCluster> entry : clusters.entrySet()) {
            JSON_CpuInfo cpuInfo = new JSON_CpuInfo();
            ICraftingCPUCluster cluster = entry.getValue();
            cpuInfo.name = cluster.web$getName();
            cpuInfo.availableStorage = cluster.web$getAvailableStorage();
            cpuInfo.usedStorage = cluster.web$getUsedStorage();
            cpuInfo.coProcessors = cluster.web$getCoProcessors();
            if (cpuInfo.isBusy = cluster.web$isBusy()) {
                cpuInfo.finalOutput = JSON_Stack.capture(grid, cluster.web$getFinalOutput());
                AE2JobTracker.JobTrackingInfo trackingInfo = AE2JobTracker.findActiveJob(cluster);
                if (cpuInfo.hasTrackingInfo = trackingInfo != null) {
                    cpuInfo.timeStarted = trackingInfo.timeStarted;
                }
            }
            cpuList.put(entry.getKey(), cpuInfo);
        }
        succeed(cpuList);
    }

}
