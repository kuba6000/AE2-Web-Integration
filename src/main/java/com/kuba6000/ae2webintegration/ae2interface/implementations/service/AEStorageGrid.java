package com.kuba6000.ae2webintegration.ae2interface.implementations.service;

import com.kuba6000.ae2webintegration.ae2interface.implementations.AEMeInventoryItem;
import com.kuba6000.ae2webintegration.ae2interface.implementations.IAEWeakObject;
import com.kuba6000.ae2webintegration.ae2interface.implementations.ItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

import appeng.api.networking.storage.IStorageGrid;

public class AEStorageGrid extends IAEWeakObject<IStorageGrid> implements IAEStorageGrid {

    public AEStorageGrid(IStorageGrid object) {
        super(object);
    }

    @Override
    public IItemList getItemStorageList() {
        return ItemList.create(
            ItemList.class,
            get().getItemInventory()
                .getStorageList());
    }

    @Override
    public IAEMeInventoryItem getItemInventory() {
        return AEMeInventoryItem.create(AEMeInventoryItem.class, get().getItemInventory());
    }
}
