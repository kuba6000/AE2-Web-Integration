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
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;

public class Order extends ISyncedRequest {

    private IAEKey itemKey;
    private long quantity;

    @Override
    boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("item") || !getParams.containsKey("quantity")) {
            noParam("item", "quantity");
            return false;
        }
        Integer hash = HTTPUtils.parseInt(getParams.get("item"));
        Long parsedQuantity = HTTPUtils.parseLong(getParams.get("quantity"));
        if (hash == null || parsedQuantity == null) {
            deny("BAD_PARAM");
            return false;
        }
        if (parsedQuantity <= 0) {
            // Verified that every platform's AE2 takes a long here, so there is no ceiling to enforce;
            // zero or negative is the only unambiguously invalid amount. A negative stack size has its
            // own meaning inside AE2, so passing one through would be undefined rather than merely odd.
            deny("INVALID_QUANTITY");
            return false;
        }
        this.quantity = parsedQuantity;
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
            setData(jobData);
            done();
        } else {
            deny("ALL_CPU_BUSY");
        }
    }

}
