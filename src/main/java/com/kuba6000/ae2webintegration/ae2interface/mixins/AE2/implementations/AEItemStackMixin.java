package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

import appeng.api.storage.data.IAEItemStack;
import cpw.mods.fml.common.registry.GameRegistry;

@Mixin(IAEItemStack.class)
public interface AEItemStackMixin extends IAEItemStack, IItemStack {

    @Override
    public default String web$getItemID() {
        return GameRegistry.findUniqueIdentifierFor(getItem())
            .toString() + ":"
            + getItemDamage();
    }

    @Override
    public default String web$getDisplayName() {
        return getItemStack().getDisplayName();
    }

    @Override
    public default long web$getStackSize() {
        return getStackSize();
    }

    @Override
    public default boolean web$isCraftable() {
        return isCraftable();
    }

    @Override
    public default long web$getCountRequestable() {
        return getCountRequestable();
    }

    @Override
    public default long web$getCountRequestableCrafts() {
        return getCountRequestableCrafts();
    }

    @Override
    public default void web$reset() {
        reset();
    }

    @Override
    public default boolean web$isSameType(IItemStack other) {
        return isSameType((IAEItemStack) other);
    }

    @Override
    public default IItemStack web$copy() {
        return (IItemStack) copy();
    }

    @Override
    public default void web$setStackSize(long size) {
        setStackSize(size);
    }
}
