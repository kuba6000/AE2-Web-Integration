package pl.kuba6000.ae2webintegration.core.interfaces.service;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

public interface IAEStorageGrid {

    IStackList web$getStorageList();

    IAEMeInventoryItem web$getInventory();

}
