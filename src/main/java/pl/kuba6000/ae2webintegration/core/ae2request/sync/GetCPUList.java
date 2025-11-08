package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.LinkedHashMap;
import java.util.Map;

import pl.kuba6000.ae2webintegration.core.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

public class GetCPUList extends ISyncedRequest {

    private static class JSON_CpuInfo {

        public boolean isBusy;
        public IStack finalOutput;
        public long availableStorage;
        public long usedStorage;
        public long coProcessors;
        public boolean hasTrackingInfo = false;
        public long timeStarted = 0L;
    }

    public static Map<String, ICraftingCPUCluster> getCPUList(IAECraftingGrid craftingGrid) {
        LinkedHashMap<String, ICraftingCPUCluster> orderedMap = new LinkedHashMap<>();
        for (ICraftingCPUCluster cpu : craftingGrid.web$getCPUs()) {
            String name = cpu.web$getName();
            orderedMap.put(name, cpu);
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
        LinkedHashMap<String, JSON_CpuInfo> cpuList = new LinkedHashMap<>(clusters.size());
        for (Map.Entry<String, ICraftingCPUCluster> entry : clusters.entrySet()) {
            JSON_CpuInfo cpuInfo = new JSON_CpuInfo();
            ICraftingCPUCluster cluster = entry.getValue();
            cpuInfo.availableStorage = cluster.web$getAvailableStorage();
            cpuInfo.usedStorage = cluster.web$getUsedStorage();
            cpuInfo.coProcessors = cluster.web$getCoProcessors();
            if (cpuInfo.isBusy = cluster.web$isBusy()) {
                cpuInfo.finalOutput = cluster.web$getFinalOutput();
                AE2JobTracker.JobTrackingInfo trackingInfo = AE2JobTracker.trackingInfoMap.get(cluster);
                if (cpuInfo.hasTrackingInfo = trackingInfo != null) {
                    cpuInfo.timeStarted = trackingInfo.timeStarted;
                }
            }
            cpuList.put(entry.getKey(), cpuInfo);
        }
        setData(cpuList);
        done();
    }

}
