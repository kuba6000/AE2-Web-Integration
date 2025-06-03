package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

import appeng.api.networking.storage.IStorageGrid;

@Mixin(value = IStorageGrid.class)
public interface AEStorageGridMixin extends IAEStorageGrid {

    @Override
    public default IItemList web$getItemStorageList() {
        return (IItemList) (Object) ((IStorageGrid) (Object) this).getItemInventory()
            .getStorageList();
    }

    @Override
    public default IAEMeInventoryItem web$getItemInventory() {
        return (IAEMeInventoryItem) ((IStorageGrid) (Object) this).getItemInventory();
    }
}
