package pl.kuba6000.ae2webintegration.core.interfaces.service;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;

public interface IAEStorageGrid {

    IItemList web$getItemStorageList();

    IAEMeInventoryItem web$getItemInventory();

}
