package com.kuba6000.ae2webintegration.mixins.AE2;

import appeng.api.networking.crafting.*;
import appeng.me.helpers.BaseActionSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuba6000.ae2webintegration.AE2JobTracker;

import appeng.api.networking.IGrid;
import appeng.me.cache.CraftingGridCache;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingGridCache.class, remap = false)
public class CraftingGridCacheMixin {

    @Final
    @Shadow
    private IGrid grid;

    @Inject(method = "submitJob", at = @At("RETURN"))
    void ae2webintegration$submitJob(ICraftingJob job, ICraftingRequester requestingMachine, ICraftingCPU target,
        boolean prioritizePower, BaseActionSource src, CallbackInfoReturnable<ICraftingLink> cir) {
        ICraftingLink link = cir.getReturnValue();
        if (link != null) { // job started successfully
            AE2JobTracker.addJob(link, (CraftingGridCache) (Object) this, grid);
        }
    }

    @Inject(
        method = "recalculateCraftingPatterns",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V", ordinal = 0, shift = At.Shift.AFTER))
    void ae2webintegration$updatePatternsStart(CallbackInfo ci) {
        AE2JobTracker.updatingPatterns((CraftingGridCache) (Object) this, grid);
    }

    @Redirect(
        method = "recalculateCraftingPatterns",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;provideCrafting(Lappeng/api/networking/crafting/ICraftingProviderHelper;)V"))
    void ae2webintegration$provideCrafting(ICraftingProvider instance,
        ICraftingProviderHelper iCraftingProviderHelper) {
        AE2JobTracker.provideCrafting((CraftingGridCache) (Object) this, grid, instance);
        instance.provideCrafting(iCraftingProviderHelper);
    }

    @Inject(method = "addCraftingOption", at = @At("HEAD"))
    void ae2webintegration$addCraftingOption(ICraftingMedium medium, ICraftingPatternDetails api, CallbackInfo ci) {
        AE2JobTracker.addCraftingOption((CraftingGridCache) (Object) this, grid, medium);
    }

    @Inject(method = "recalculateCraftingPatterns", at = @At(value = "TAIL"))
    void ae2webintegration$updatePatternsEnd(CallbackInfo ci) {
        AE2JobTracker.doneUpdatingPatterns((CraftingGridCache) (Object) this, grid);
    }

}
