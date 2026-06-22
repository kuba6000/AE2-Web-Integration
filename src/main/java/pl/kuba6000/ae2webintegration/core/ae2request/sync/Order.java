package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import static pl.kuba6000.ae2webintegration.core.AE2Controller.hashcodeToStack;

import java.util.Map;
import java.util.concurrent.Future;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

public class Order extends ISyncedRequest {

    private IAEKey itemKey;
    private long quantity;

    @Override
    boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("item") || !getParams.containsKey("quantity")) {
            noParam("item", "quantity");
            return false;
        }
        int hash = Integer.parseInt(getParams.get("item"));
        this.quantity = Integer.parseInt(getParams.get("quantity"));
        IAEGenericStack stack = hashcodeToStack.get(hash);
        if (stack == null) {
            deny("ITEM_NOT_FOUND");
            return false;
        }
        this.itemKey = stack.web$what();
        return true;
    }

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        if (!itemKey.web$isCraftable(grid)) {
            deny("ITEM_NOT_FOUND");
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
            Future<IAECraftingJob> job = craftingGrid.web$beginCraftingJob(grid, itemKey, quantity);

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
            deny("ALL_CPU_BUSY");
        }
    }

}
