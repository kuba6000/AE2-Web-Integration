package com.kuba6000.ae2webintegration.ae2request.sync;

import java.util.ArrayList;
import java.util.Map;

import com.kuba6000.ae2webintegration.AE2Controller;
import com.kuba6000.ae2webintegration.api.JSON_DetailedItem;

import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.Grid;
import cpw.mods.fml.common.registry.GameRegistry;

public class GetItems extends ISyncedRequest {

    @Override
    public boolean init(Map<String, String> getParams) {
        return true;
    }

    @Override
    public void handle(Grid grid) {
        IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
        IMEMonitor<IAEItemStack> monitor = storageGrid.getItemInventory();
        AE2Controller.globalItemList = monitor.getStorageList();
        AE2Controller.hashcodeToAEItemStack.clear();
        ArrayList<JSON_DetailedItem> items = new ArrayList<>();
        for (IAEItemStack iaeItemStack : AE2Controller.globalItemList) {
            int hash;
            AE2Controller.hashcodeToAEItemStack.put(hash = iaeItemStack.hashCode(), iaeItemStack);
            JSON_DetailedItem detailedItem = new JSON_DetailedItem();
            detailedItem.itemid = GameRegistry.findUniqueIdentifierFor(iaeItemStack.getItem())
                .toString() + ":"
                + iaeItemStack.getItemDamage();
            detailedItem.itemname = iaeItemStack.getItemStack()
                .getDisplayName();
            detailedItem.quantity = iaeItemStack.getStackSize();
            detailedItem.craftable = iaeItemStack.isCraftable();
            detailedItem.hashcode = hash;
            items.add(detailedItem);
        }
        setData(items);
        done();
    }

}
