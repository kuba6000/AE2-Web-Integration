package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

import appeng.me.service.StorageService;

@Mixin(value = StorageService.class)
public class AEStorageGridMixin implements IAEStorageGrid {

    @Override
    public IItemList web$getItemStorageList() {
        return (IItemList) (Object) ((StorageService) (Object) this).getInventory()
            .getAvailableStacks();
    }

    @Override
    public IAEMeInventoryItem web$getItemInventory() {
        return (IAEMeInventoryItem) ((StorageService) (Object) this).getInventory();
    }
}
