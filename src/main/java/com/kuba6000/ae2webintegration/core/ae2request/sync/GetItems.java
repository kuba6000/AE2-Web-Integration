package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.api.JSON_DetailedItem;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

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
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        IItemList storageList = storageGrid.web$getItemStorageList();
        Set<IAEKey> craftables = craftingGrid.web$getCraftables(null);
        AE2Controller.hashcodeToAEKey.clear();
        ArrayList<JSON_DetailedItem> items = new ArrayList<>();
        for (Object2LongMap.Entry<IAEKey> entry : storageList) {
            IAEKey stack = entry.getKey();
            int hash;
            AE2Controller.hashcodeToAEKey.put(hash = stack.hashCode(), stack);
            JSON_DetailedItem detailedItem = new JSON_DetailedItem();
            detailedItem.itemid = stack.web$getItemID();
            detailedItem.itemname = stack.web$getDisplayName();
            detailedItem.quantity = entry.getLongValue();
            detailedItem.craftable = craftables.remove(stack);
            detailedItem.hashcode = hash;
            items.add(detailedItem);
        }
        for (IAEKey craftable : craftables) {
            int hash;
            // if (storageList.web$findPrecise(craftable) == 0
            // && !AE2Controller.hashcodeToAEKey.containsKey(hash = craftable.hashCode())) {
            AE2Controller.hashcodeToAEKey.put(hash = craftable.hashCode(), craftable);
            JSON_DetailedItem detailedItem = new JSON_DetailedItem();
            detailedItem.itemid = craftable.web$getItemID();
            detailedItem.itemname = craftable.web$getDisplayName();
            detailedItem.quantity = 0;
            detailedItem.craftable = true;
            detailedItem.hashcode = hash;
            items.add(detailedItem);
            // }
        }
        setData(items);
        done();
    }

}
