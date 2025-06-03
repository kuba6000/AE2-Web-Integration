package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

import appeng.api.networking.crafting.ICraftingPatternDetails;

@Mixin(value = ICraftingPatternDetails.class, remap = false)
public interface AECraftingPatternDetailsMixin extends IAECraftingPatternDetails {

    @Override
    public default IItemStack[] web$getCondensedOutputs() {
        return (IItemStack[]) ((ICraftingPatternDetails) (Object) this).getCondensedOutputs();
    }
}
