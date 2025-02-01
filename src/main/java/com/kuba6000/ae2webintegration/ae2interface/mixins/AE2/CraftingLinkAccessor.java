package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.crafting.CraftingLink;

@Mixin(value = CraftingLink.class, remap = false)
public interface CraftingLinkAccessor {

    @Invoker
    ICraftingCPU callGetCpu();

}
