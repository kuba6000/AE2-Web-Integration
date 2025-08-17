package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.Map;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.api.JSON_DetailedItem;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
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
        IItemList storageList = storageGrid.web$getItemStorageList();
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
            detailedItem.craftable = stack.web$isCraftable(grid);
            detailedItem.hashcode = hash;
            items.add(detailedItem);
        }
        setData(items);
        done();
    }

}
