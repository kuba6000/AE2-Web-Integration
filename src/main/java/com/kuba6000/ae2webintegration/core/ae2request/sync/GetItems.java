package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.Map;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.api.JSON_DetailedItem;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public class GetItems extends ISyncedRequest {

    @Override
    public boolean init(Map<String, String> getParams) {
        return true;
    }

    @Override
    public void handle(IAEGrid grid) {
        IAEStorageGrid storageGrid = grid.getStorageGrid();
        IItemList storageList = storageGrid.getItemStorageList();
        AE2Controller.hashcodeToAEItemStack.clear();
        ArrayList<JSON_DetailedItem> items = new ArrayList<>();
        for (IItemStack stack : storageList) {
            int hash;
            AE2Controller.hashcodeToAEItemStack.put(hash = stack.hashCode(), stack);
            JSON_DetailedItem detailedItem = new JSON_DetailedItem();
            detailedItem.itemid = stack.getItemID();
            detailedItem.itemname = stack.getDisplayName();
            detailedItem.quantity = stack.getStackSize();
            detailedItem.craftable = stack.isCraftable();
            detailedItem.hashcode = hash;
            items.add(detailedItem);
        }
        setData(items);
        done();
    }

}
