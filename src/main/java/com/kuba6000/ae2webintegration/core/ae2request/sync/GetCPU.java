package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.AE2JobTracker;
import com.kuba6000.ae2webintegration.core.api.JSON_CompactedItem;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

public class GetCPU extends ISyncedRequest {

    private static class JSON_ClusterData {

        public long size;
        public boolean isBusy;
        public IAEGenericStack finalOutput;
        public ArrayList<JSON_CompactedItem> items;
        public boolean hasTrackingInfo = false;
        public long timeStarted = 0L;
        public long timeElapsed = 0L;
    }

    String cpuName = null;

    @Override
    boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("cpu")) {
            noParam("cpu");
            return false;
        }
        cpuName = getParams.get("cpu");
        return true;
    }

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();

        ICraftingCPUCluster cpu = GetCPUList.getCPUList(craftingGrid)
            .get(cpuName);
        if (cpu == null) {
            deny("CPU_NOT_FOUND");
            return;
        }

        JSON_ClusterData clusterData = new JSON_ClusterData();
        clusterData.size = cpu.web$getAvailableStorage();
        clusterData.isBusy = cpu.web$isBusy();
        if (clusterData.isBusy) {
            clusterData.finalOutput = cpu.web$getFinalOutput();
            AE2JobTracker.JobTrackingInfo trackingInfo = AE2JobTracker.trackingInfoMap.get(cpu);
            clusterData.hasTrackingInfo = trackingInfo != null;

            HashMap<IAEKey, JSON_CompactedItem> prep = new HashMap<>();
            IItemList items = AE2Controller.AE2Interface.web$createItemList();
            cpu.web$getAllItems(items);
            for (Object2LongMap.Entry<IAEKey> entry : items) {
                IAEKey key = entry.getKey();
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(key);
                compactedItem.active = cpu.web$getActiveItems(key);
                compactedItem.pending = cpu.web$getPendingItems(key);
                compactedItem.stored = cpu.web$getStorageItems(key);
                prep.put(key, compactedItem);
            }

            if (clusterData.hasTrackingInfo) {
                clusterData.timeStarted = trackingInfo.timeStarted;
                clusterData.timeElapsed = (System.currentTimeMillis()) - clusterData.timeStarted;
                for (IAEKey stack : trackingInfo.timeSpentOn.keySet()) {
                    JSON_CompactedItem compactedItem = prep
                        .computeIfAbsent(stack, k -> JSON_CompactedItem.create(stack));
                    compactedItem.timeSpentCrafting += trackingInfo.getTimeSpentOn(stack);
                    compactedItem.craftedTotal += trackingInfo.craftedTotal.getOrDefault(stack, 0L);
                    compactedItem.shareInCraftingTime += trackingInfo.getShareInCraftingTime(stack);
                    compactedItem.shareInCraftingTimeCombined = Math
                        .min(((double) compactedItem.timeSpentCrafting) / (double) clusterData.timeElapsed, 1d);
                    compactedItem.craftsPerSec = (double) compactedItem.craftedTotal
                        / (compactedItem.timeSpentCrafting / 1000d);
                }
            }

            clusterData.items = new ArrayList<>(prep.values());
            // TODO Move sorting to javascript!
            clusterData.items.sort((i1, i2) -> {
                if (i1.active > 0 && i2.active > 0) return Long.compare(i2.active, i1.active);
                else if (i1.active > 0 && i2.active == 0) return -1;
                else if (i1.active == 0 && i2.active > 0) return 1;
                if (i1.pending > 0 && i2.pending > 0) return Long.compare(i2.pending, i1.pending);
                else if (i1.pending > 0 && i2.pending == 0) return -1;
                else if (i1.pending == 0 && i2.pending > 0) return 1;
                return Long.compare(i2.stored, i1.stored);
            });

        }

        setData(clusterData);
        done();
    }

}
