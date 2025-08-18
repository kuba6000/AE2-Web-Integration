package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.kuba6000.ae2webintegration.ae2interface.accessors.IProviderState;

import appeng.api.networking.crafting.ICraftingProvider;

@Mixin(targets = "appeng.me.service.helpers.NetworkCraftingProviders$ProviderState", remap = false)
public class ProviderStateMixin implements IProviderState {

    @Shadow
    @Final
    private ICraftingProvider provider;

    @Override
    public ICraftingProvider web$getProvider() {
        return provider;
    }
}
