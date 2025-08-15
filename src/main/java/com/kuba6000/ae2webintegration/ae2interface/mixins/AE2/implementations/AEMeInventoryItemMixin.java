package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

import appeng.api.config.Actionable;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import appeng.me.helpers.BaseActionSource;

@Mixin(value = IMEInventory.class)
public interface AEMeInventoryItemMixin extends IAEMeInventoryItem {

    @Override
    public default IItemStack web$extractItems(IItemStack stack, AEActionable mode, IAEGrid grid) {
        return (IItemStack) ((IMEInventory) (Object) this).extractItems(
            (IAEStack) stack,
            mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE,
            (BaseActionSource) grid.web$getPlayerSource());
    }

    @Override
    public default IItemStack web$getAvailableItem(IItemStack stack, IAEGrid grid) {
        return web$extractItems(stack, AEActionable.SIMULATE, grid);
    }
}
