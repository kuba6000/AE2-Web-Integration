package com.kuba6000.ae2webintegration.ae2request.sync;

import static com.kuba6000.ae2webintegration.api.JSON_Item.create;

import java.util.LinkedHashMap;
import java.util.Map;

import com.kuba6000.ae2webintegration.AE2JobTracker;
import com.kuba6000.ae2webintegration.api.JSON_Item;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.me.Grid;
import appeng.me.cluster.implementations.CraftingCPUCluster;

public class GetCPUList extends ISyncedRequest {

    private static class JSON_CpuInfo {

        public long size;
        public boolean isBusy;
        public JSON_Item finalOutput;
        public boolean hasTrackingInfo = false;
        public long timeStarted = 0L;
    }

    public static Map<String, CraftingCPUCluster> getCPUList(ICraftingGrid craftingGrid) {
        LinkedHashMap<String, CraftingCPUCluster> orderedMap = new LinkedHashMap<>();
        int id = 1;
        for (ICraftingCPU cpu : craftingGrid.getCpus()) {
            if (cpu instanceof CraftingCPUCluster cluster) {
                String name = cluster.getName();
                if (name.isEmpty()) name = "CPU #" + id;
                id++;
                orderedMap.put(name, cluster);
            }
        }
        return orderedMap;
    }

    @Override
    public boolean init(Map<String, String> getParams) {
        return true;
    }

    @Override
    public void handle(Grid grid) {
        Map<String, CraftingCPUCluster> clusters = getCPUList(grid.getCache(ICraftingGrid.class));
        LinkedHashMap<String, JSON_CpuInfo> cpuList = new LinkedHashMap<>(clusters.size());
        for (Map.Entry<String, CraftingCPUCluster> entry : clusters.entrySet()) {
            JSON_CpuInfo cpuInfo = new JSON_CpuInfo();
            CraftingCPUCluster cluster = entry.getValue();
            cpuInfo.size = cluster.getAvailableStorage();
            if (cpuInfo.isBusy = cluster.isBusy()) {
                cpuInfo.finalOutput = create(cluster.getFinalOutput());
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
