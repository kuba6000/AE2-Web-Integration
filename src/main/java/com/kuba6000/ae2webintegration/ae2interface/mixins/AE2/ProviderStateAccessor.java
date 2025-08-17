package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.networking.crafting.ICraftingProvider;

@Mixin(targets = "appeng.me.service.helpers.NetworkCraftingProviders.ProviderState", remap = false)
public interface ProviderStateAccessor {

    @Accessor
    public default ICraftingProvider getProvider() {
        throw new UnsupportedOperationException("Mixin failed to apply.");
    }

}
