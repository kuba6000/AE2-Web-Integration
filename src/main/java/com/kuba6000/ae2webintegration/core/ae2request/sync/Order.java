package com.kuba6000.ae2webintegration.core.ae2request.sync;

import static com.kuba6000.ae2webintegration.core.AE2Controller.hashcodeToAEItemStack;

import java.util.Map;
import java.util.concurrent.Future;

import com.google.gson.JsonObject;
import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public class Order extends ISyncedRequest {

    private IItemStack item;

    @Override
    public boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("item") || !getParams.containsKey("quantity")) {
            noParam("item", "quantity");
            return false;
        }
        int hash = Integer.parseInt(getParams.get("item"));
        int quantity = Integer.parseInt(getParams.get("quantity"));
        this.item = hashcodeToAEItemStack.get(hash);
        if (this.item == null || !this.item.web$isCraftable()) {
            deny("ITEM_NOT_FOUND");
            return false;
        }
        this.item = this.item.web$copy();
        this.item.web$setStackSize(quantity);
        return true;
    }

    @Override
    public void handle(IAEGrid grid) {
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        boolean allBusy = true;
        for (ICraftingCPUCluster cpu : craftingGrid.web$getCPUs()) {
            if (!cpu.web$isBusy()) {
                allBusy = false;
                break;
            }
        }
        if (!allBusy) {
            IAEStorageGrid storageGrid = grid.web$getStorageGrid();
            final IItemList itemList = storageGrid.web$getItemStorageList();
            IItemStack realItem = itemList.web$findPrecise(this.item);
            if (realItem != null && realItem.web$isCraftable()) {
                Future<IAECraftingJob> job = craftingGrid.web$beginCraftingJob(grid, this.item);

                int jobID = AE2Controller.getNextJobID();
                AE2Controller.jobs.put(jobID, job);
                JsonObject jobData = new JsonObject();
                jobData.addProperty("jobID", jobID);
                if (AE2Controller.jobs.size() > 3) {
                    int toDeleteBelowAndEqual = jobID - 3;
                    AE2Controller.jobs.entrySet()
                        .removeIf(integerFutureEntry -> integerFutureEntry.getKey() <= toDeleteBelowAndEqual);
                }
                setData(jobData);
                done();
            } else {
                deny("ITEM_NOT_FOUND");
            }
        } else {
            deny("ALL_CPU_BUSY");
        }
    }

}
