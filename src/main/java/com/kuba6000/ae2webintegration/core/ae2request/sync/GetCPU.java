package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.AE2JobTracker;
import com.kuba6000.ae2webintegration.core.api.JSON_CompactedItem;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

public class GetCPU extends ISyncedRequest {

    private static class JSON_ClusterData {

        public long size;
        public boolean isBusy;
        public IItemStack finalOutput;
        public ArrayList<JSON_CompactedItem> items;
        public boolean hasTrackingInfo = false;
        public long timeStarted = 0L;
        public long timeElapsed = 0L;
    }

    String cpuName = null;

    @Override
    public boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("cpu")) {
            noParam("cpu");
            return false;
        }
        cpuName = getParams.get("cpu");
        return true;
    }

    @Override
    public void handle(IAEGrid grid) {
        IAECraftingGrid craftingGrid = grid.getCraftingGrid();

        ICraftingCPUCluster cpu = GetCPUList.getCPUList(craftingGrid)
            .get(cpuName);
        if (cpu == null) {
            deny("CPU_NOT_FOUND");
            return;
        }

        JSON_ClusterData clusterData = new JSON_ClusterData();
        clusterData.size = cpu.getAvailableStorage();
        clusterData.isBusy = cpu.isBusy();
        if (clusterData.isBusy) {
            clusterData.finalOutput = cpu.getFinalOutput();
            AE2JobTracker.JobTrackingInfo trackingInfo = AE2JobTracker.trackingInfoMap.get(cpu);
            clusterData.hasTrackingInfo = trackingInfo != null;

            HashMap<JSON_CompactedItem, JSON_CompactedItem> prep = new HashMap<>();
            IItemList items = AE2Controller.AE2Interface.createItemList();
            cpu.getActiveItems(items);
            for (IItemStack itemStack : items) {
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(itemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).active += itemStack.getStackSize();
            }
            items = AE2Controller.AE2Interface.createItemList();
            cpu.getPendingItems(items);
            for (IItemStack itemStack : items) {
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(itemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).pending += itemStack.getStackSize();
            }
            items = AE2Controller.AE2Interface.createItemList();
            cpu.getStorageItems(items);
            for (IItemStack itemStack : items) {
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(itemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).stored += itemStack.getStackSize();
            }

            if (clusterData.hasTrackingInfo) {
                clusterData.timeStarted = trackingInfo.timeStarted;
                clusterData.timeElapsed = (System.currentTimeMillis()) - clusterData.timeStarted;
                for (IItemStack stack : trackingInfo.timeSpentOn.keySet()) {
                    JSON_CompactedItem compactedItem = JSON_CompactedItem.create(stack);
                    JSON_CompactedItem finalCompactedItem = compactedItem;
                    compactedItem = prep.computeIfAbsent(compactedItem, k -> finalCompactedItem);
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
