package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;

import appeng.api.crafting.IPatternDetails;

@Mixin(value = IPatternDetails.class, remap = false)
public interface AECraftingPatternDetailsMixin extends IAECraftingPatternDetails {

    @Override
    public default IAEGenericStack[] web$getCondensedOutputs() {
        return (IAEGenericStack[]) (Object) ((IPatternDetails) (Object) this).getOutputs();
    }
}
