package com.kuba6000.ae2webintegration.ae2interface.implementations.service;

import com.kuba6000.ae2webintegration.ae2interface.implementations.AEMeInventoryItem;
import com.kuba6000.ae2webintegration.ae2interface.implementations.IAEObject;
import com.kuba6000.ae2webintegration.ae2interface.implementations.ItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

import appeng.api.AEApi;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.channels.IItemStorageChannel;

public class AEStorageGrid extends IAEObject<IStorageGrid> implements IAEStorageGrid {

    public AEStorageGrid(IStorageGrid object) {
        super(object);
    }

    @Override
    public IItemList getItemStorageList() {
        return new ItemList(
            get().getInventory(
                AEApi.instance()
                    .storage()
                    .getStorageChannel(IItemStorageChannel.class))
                .getStorageList());
    }

    @Override
    public IAEMeInventoryItem getItemInventory() {
        return new AEMeInventoryItem(
            get().getInventory(
                AEApi.instance()
                    .storage()
                    .getStorageChannel(IItemStorageChannel.class)));
    }
}
