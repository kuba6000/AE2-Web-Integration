package com.kuba6000.ae2webintegration.ae2request.sync;

import static com.kuba6000.ae2webintegration.api.JSON_Item.create;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.kuba6000.ae2webintegration.AE2JobTracker;
import com.kuba6000.ae2webintegration.api.JSON_CompactedItem;
import com.kuba6000.ae2webintegration.api.JSON_Item;

import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.Grid;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.util.item.HashBasedItemList;

public class GetCPU extends ISyncedRequest {

    private static class JSON_ClusterData {

        public long size;
        public long usedSize;
        public boolean isBusy;
        public JSON_Item finalOutput;
        public ArrayList<JSON_CompactedItem> items;
        public boolean hasTrackingInfo = false;
        public long timeStarted = 0L;
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
    public void handle(Grid grid) {
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);

        CraftingCPUCluster cpu = GetCPUList.getCPUList(craftingGrid)
            .get(cpuName);
        if (cpu == null) {
            deny("CPU_NOT_FOUND");
            return;
        }

        JSON_ClusterData clusterData = new JSON_ClusterData();
        clusterData.size = cpu.getAvailableStorage();
        clusterData.usedSize = cpu.getUsedStorage();
        clusterData.isBusy = cpu.isBusy();
        if (clusterData.isBusy) {
            clusterData.finalOutput = create(cpu.getFinalOutput());
            AE2JobTracker.JobTrackingInfo trackingInfo = AE2JobTracker.trackingInfoMap.get(cpu);
            clusterData.hasTrackingInfo = trackingInfo != null;

            HashMap<JSON_CompactedItem, JSON_CompactedItem> prep = new HashMap<>();
            HashBasedItemList items = new HashBasedItemList();
            cpu.getListOfItem(items, CraftingItemList.ACTIVE);
            for (IAEItemStack itemStack : items) {
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(itemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).active += itemStack.getStackSize();
            }
            items = new HashBasedItemList();
            cpu.getListOfItem(items, CraftingItemList.PENDING);
            for (IAEItemStack itemStack : items) {
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(itemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).pending += itemStack.getStackSize();
            }
            items = new HashBasedItemList();
            cpu.getListOfItem(items, CraftingItemList.STORAGE);
            for (IAEItemStack itemStack : items) {
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(itemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).stored += itemStack.getStackSize();
            }

            if (clusterData.hasTrackingInfo) {
                clusterData.timeStarted = trackingInfo.timeStarted;
                for (IAEItemStack iaeItemStack : trackingInfo.timeSpentOn.keySet()) {
                    JSON_CompactedItem compactedItem = JSON_CompactedItem.create(iaeItemStack);
                    JSON_CompactedItem finalCompactedItem = compactedItem;
                    compactedItem = prep.computeIfAbsent(compactedItem, k -> finalCompactedItem);
                    compactedItem.timeSpentCrafting += trackingInfo.getTimeSpentOn(iaeItemStack);
                    compactedItem.craftedTotal += trackingInfo.craftedTotal.getOrDefault(iaeItemStack, 0L);
                    compactedItem.shareInCraftingTime += trackingInfo.getShareInCraftingTime(iaeItemStack);
                    compactedItem.craftsPerSec = (double) compactedItem.craftedTotal
                        / (compactedItem.timeSpentCrafting / 1e9d);
                }
            }

            clusterData.items = new ArrayList<>(prep.values());

        }

        setData(clusterData);
        done();
    }

}
