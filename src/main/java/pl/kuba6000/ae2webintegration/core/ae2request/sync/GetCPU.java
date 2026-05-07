package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.api.JSON_CompactedItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

public class GetCPU extends ISyncedRequest {

    private static class JSON_ClusterData {

        public long size;
        public boolean isBusy;
        public IStack finalOutput;
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

            HashMap<JSON_CompactedItem, JSON_CompactedItem> prep = new HashMap<>();
            IItemList items = AE2Controller.AE2Interface.web$createItemList();
            cpu.web$getActiveItems(items);
            for (IStack itemStack : items) {
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(itemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).active += itemStack.web$getStackSize();
            }
            items = AE2Controller.AE2Interface.web$createItemList();
            cpu.web$getPendingItems(items);
            for (IStack itemStack : items) {
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(itemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).pending += itemStack.web$getStackSize();
            }
            items = AE2Controller.AE2Interface.web$createItemList();
            cpu.web$getStorageItems(items);
            for (IStack itemStack : items) {
                JSON_CompactedItem compactedItem = JSON_CompactedItem.create(itemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).stored += itemStack.web$getStackSize();
            }

            if (clusterData.hasTrackingInfo) {
                clusterData.timeStarted = trackingInfo.timeStarted;
                clusterData.timeElapsed = (System.currentTimeMillis()) - clusterData.timeStarted;
                for (IStack stack : trackingInfo.timeSpentOn.keySet()) {
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
