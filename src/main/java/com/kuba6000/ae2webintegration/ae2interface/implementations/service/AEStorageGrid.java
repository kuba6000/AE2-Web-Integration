package com.kuba6000.ae2webintegration.ae2interface.implementations.service;

import com.kuba6000.ae2webintegration.ae2interface.implementations.AEMeInventoryItem;
import com.kuba6000.ae2webintegration.ae2interface.implementations.IAEObject;
import com.kuba6000.ae2webintegration.ae2interface.implementations.ItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

import appeng.api.networking.storage.IStorageGrid;

public class AEStorageGrid extends IAEObject<IStorageGrid> implements IAEStorageGrid {

    public AEStorageGrid(IStorageGrid object) {
        super(object);
    }

    @Override
    public IItemList getItemStorageList() {
        return new ItemList(
            get().getItemInventory()
                .getStorageList());
    }

    @Override
    public IAEMeInventoryItem getItemInventory() {
        return new AEMeInventoryItem(get().getItemInventory());
    }
}
