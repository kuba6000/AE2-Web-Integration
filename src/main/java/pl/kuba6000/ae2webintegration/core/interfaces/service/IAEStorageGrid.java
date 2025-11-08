package pl.kuba6000.ae2webintegration.core.interfaces.service;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;

public interface IAEStorageGrid {

    IItemList web$getItemStorageList();

    IItemList web$getFluidStorageList();

    IAEMeInventoryItem web$getItemInventory();

}
