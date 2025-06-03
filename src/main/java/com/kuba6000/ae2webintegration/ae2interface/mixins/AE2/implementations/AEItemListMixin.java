package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemContainer;
import appeng.api.storage.data.IItemList;

@Mixin(value = IItemList.class, remap = false)
public interface AEItemListMixin<StackType extends IAEStack>
    extends IItemContainer<StackType>, com.kuba6000.ae2webintegration.core.interfaces.IItemList {

    @Override
    default IItemStack web$findPrecise(IItemStack stack) {
        return (IItemStack) findPrecise((StackType) stack);
    }

}
