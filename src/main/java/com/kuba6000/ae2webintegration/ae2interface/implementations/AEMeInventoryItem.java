package com.kuba6000.ae2webintegration.ae2interface.implementations;

import com.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

import appeng.api.config.Actionable;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEItemStack;

public class AEMeInventoryItem extends IAEObject<IMEInventory<IAEItemStack>> implements IAEMeInventoryItem {

    public AEMeInventoryItem(IMEInventory<IAEItemStack> object) {
        super(object);
    }

    @Override
    public IItemStack extractItems(IItemStack stack, AEActionable mode, IAEGrid grid) {
        IAEItemStack istack = get().extractItems(
            ((ItemStack) stack).stack,
            mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE,
            ((AEGrid) grid).getPlayerSource());
        return istack == null ? null : new ItemStack(istack);
    }

    @Override
    public IItemStack getAvailableItem(IItemStack stack) {
        IAEItemStack istack = get().getAvailableItem(((ItemStack) stack).stack);
        return istack == null ? null : new ItemStack(istack);
    }
}
