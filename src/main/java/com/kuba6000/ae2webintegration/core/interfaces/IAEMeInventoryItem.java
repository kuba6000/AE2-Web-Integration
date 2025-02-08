package com.kuba6000.ae2webintegration.core.interfaces;

import com.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;

public interface IAEMeInventoryItem {

    IItemStack extractItems(IItemStack stack, AEActionable mode, IAEGrid grid);

    IItemStack getAvailableItem(IItemStack stack, IAEGrid grid);

}
