package com.kuba6000.ae2webintegration.core.ae2request.sync;

import static com.kuba6000.ae2webintegration.core.AE2Controller.hashcodeToAEKey;

import java.util.Map;
import java.util.concurrent.Future;

import com.google.gson.JsonObject;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public class Order extends ISyncedRequest {

    private IAEKey item;
    private long quantity;

    @Override
    boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("item") || !getParams.containsKey("quantity")) {
            noParam("item", "quantity");
            return false;
        }
        int hash = Integer.parseInt(getParams.get("item"));
        this.quantity = Integer.parseInt(getParams.get("quantity"));
        this.item = hashcodeToAEKey.get(hash);
        if (this.item == null) {
            deny("ITEM_NOT_FOUND");
            return false;
        }
        return true;
    }

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
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
            // long realItem = itemList.web$findPrecise(this.item);
            if (/* realItem > 0L && */this.item.web$isCraftable(grid)) {
                Future<IAECraftingJob> job = craftingGrid.web$beginCraftingJob(grid, this.item, this.quantity);

                int jobID = gridData.addJob(job);
                JsonObject jobData = new JsonObject();
                jobData.addProperty("jobID", jobID);
                if (gridData.jobs.size() > 3) {
                    int toDeleteBelowAndEqual = jobID - 3;
                    gridData.jobs.entrySet()
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
