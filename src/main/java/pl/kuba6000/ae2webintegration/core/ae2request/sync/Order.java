package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import static pl.kuba6000.ae2webintegration.core.AE2Controller.hashcodeToStack;

import java.util.Map;
import java.util.concurrent.Future;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public class Order extends ISyncedRequest {

    private IStack item;

    @Override
    boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("item") || !getParams.containsKey("quantity")) {
            noParam("item", "quantity");
            return false;
        }
        int hash = Integer.parseInt(getParams.get("item"));
        int quantity = Integer.parseInt(getParams.get("quantity"));
        this.item = hashcodeToStack.get(hash);
        if (this.item == null || !this.item.web$isCraftable()) {
            deny("ITEM_NOT_FOUND");
            return false;
        }
        this.item = this.item.web$copy();
        this.item.web$setStackSize(quantity);
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
            IStack realItem = findCraftableStack(storageGrid, this.item);
            if (realItem != null) {
                Future<IAECraftingJob> job = craftingGrid.web$beginCraftingJob(grid, this.item);

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

    private static IStack findCraftableStack(IAEStorageGrid storageGrid, IStack stack) {
        IItemList list = stack.web$isItem() ? storageGrid.web$getItemStorageList()
            : storageGrid.web$getFluidStorageList();
        IStack found = list.web$findPrecise(stack);
        return found != null && found.web$isCraftable() ? found : null;
    }

}
