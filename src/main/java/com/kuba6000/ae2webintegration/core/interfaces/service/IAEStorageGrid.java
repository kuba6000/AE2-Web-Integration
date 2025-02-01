package com.kuba6000.ae2webintegration.core.interfaces.service;

import com.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;

public interface IAEStorageGrid {

    IItemList getItemStorageList();

    IAEMeInventoryItem getItemInventory();

}
