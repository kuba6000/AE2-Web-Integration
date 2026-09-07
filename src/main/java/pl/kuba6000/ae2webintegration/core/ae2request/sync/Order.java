package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import static pl.kuba6000.ae2webintegration.core.AE2Controller.itemIdentities;

import java.util.Map;
import java.util.concurrent.Future;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;

public class Order extends ISyncedRequest {

    private StableItemKey requestedKey;
    private long quantity;

    @Override
    boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("itemKey") || !getParams.containsKey("quantity")) {
            noParam("itemKey", "quantity");
            return false;
        }
        try {
            requestedKey = StableItemKey.parse(getParams.get("itemKey"));
        } catch (IllegalArgumentException e) {
            deny("BAD_PARAM");
            return false;
        }
        Long parsedQuantity = HTTPUtils.parseLong(getParams.get("quantity"));
        if (parsedQuantity == null) {
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
        return true;
    }

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        IAEKey itemKey;
        try {
            itemKey = itemIdentities.resolve(requestedKey);
        } catch (ItemIdentityRegistry.Ambiguous e) {
            deny("AMBIGUOUS_ITEM_KEY");
            return;
        }
        if (itemKey == null) {
            deny("ITEM_IDENTITY_UNKNOWN");
            return;
        }
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        if (!craftingGrid.web$isCurrentlyCraftable(itemKey)) {
            deny("ITEM_NOT_FOUND");
            return;
        }
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
            succeed(jobData);
        } else {
            deny("ALL_CPU_BUSY");
        }
    }

}
