package com.kuba6000.ae2webintegration.mixins.AE2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kuba6000.ae2webintegration.AE2JobTracker;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.BaseActionSource;
import appeng.me.cache.CraftingGridCache;

@Mixin(value = CraftingGridCache.class, remap = false)
public class CraftingGridCacheMixin {

    @Inject(method = "submitJob", at = @At("RETURN"))
    void ae2webintegration$submitJob(ICraftingJob job, ICraftingRequester requestingMachine, ICraftingCPU target,
        boolean prioritizePower, BaseActionSource src, CallbackInfoReturnable<ICraftingLink> cir) {
        ICraftingLink link = cir.getReturnValue();
        if (link != null) { // job started successfully
            AE2JobTracker.addJob(link);
        }
    }

}
