package com.kuba6000.ae2webintegration.ae2sync;

import static com.kuba6000.ae2webintegration.utils.GSONUtils.convertToGSONItem;

import java.util.ArrayList;
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

        public String name;
        public long size;
        public long usedSize;
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
        ArrayList<JSON_CpuInfo> cpuList = new ArrayList<>(clusters.size());
        for (Map.Entry<String, CraftingCPUCluster> entry : clusters.entrySet()) {
            JSON_CpuInfo cpuInfo = new JSON_CpuInfo();
            CraftingCPUCluster cluster = entry.getValue();
            cpuInfo.name = entry.getKey();
            cpuInfo.size = cluster.getAvailableStorage();
            cpuInfo.usedSize = cluster.getUsedStorage();
            if (cpuInfo.isBusy = cluster.isBusy()) {
                cpuInfo.finalOutput = convertToGSONItem(cluster.getFinalOutput());
                AE2JobTracker.JobTrackingInfo trackingInfo = AE2JobTracker.trackingInfoMap.get(cluster);
                if (cpuInfo.hasTrackingInfo = trackingInfo != null) {
                    cpuInfo.timeStarted = trackingInfo.timeStarted;
                }
            }
            cpuList.add(cpuInfo);
        }
        setData(cpuList);
        done();
    }

    @Override
    Object getData() {
        return null;
    }

}
