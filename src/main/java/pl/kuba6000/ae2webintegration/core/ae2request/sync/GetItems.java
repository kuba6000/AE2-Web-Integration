package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.Map;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.JSON_DetailedItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public class GetItems extends ISyncedRequest {

    @Override
    boolean init(Map<String, String> getParams) {
        return true;
    }

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        IAEStorageGrid storageGrid = grid.web$getStorageGrid();
        IItemList storageList = storageGrid.web$getItemStorageList();
        AE2Controller.hashcodeToAEItemStack.clear();
        ArrayList<JSON_DetailedItem> items = new ArrayList<>();
        for (IStack stack : storageList) {
            int hash;
            AE2Controller.hashcodeToAEItemStack.put(hash = stack.hashCode(), stack);
            JSON_DetailedItem detailedItem = new JSON_DetailedItem();
            detailedItem.itemid = stack.web$getItemID();
            detailedItem.itemname = stack.web$getDisplayName();
            detailedItem.quantity = stack.web$getStackSize();
            detailedItem.craftable = stack.web$isCraftable();
            detailedItem.hashcode = hash;
            items.add(detailedItem);
        }
        setData(items);
        done();
    }

}
