package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kuba6000.ae2webintegration.ae2interface.CraftingMediumTracker;
import com.kuba6000.ae2webintegration.ae2interface.implementations.AECraftingCPUCluster;
import com.kuba6000.ae2webintegration.ae2interface.implementations.AEGrid;
import com.kuba6000.ae2webintegration.ae2interface.implementations.service.AECraftingGrid;
import com.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;

@Mixin(value = CraftingGridCache.class, remap = false)
public class CraftingGridCacheMixin {

    @Final
    @Shadow
    private IGrid grid;

    @Inject(method = "submitJob", at = @At("RETURN"))
    void ae2webintegration$submitJob(final ICraftingJob job, final ICraftingRequester requestingMachine,
        final ICraftingCPU target, final boolean prioritizePower, final IActionSource src,
        CallbackInfoReturnable<ICraftingLink> cir) {
        ICraftingLink link = cir.getReturnValue();
        if (link != null) { // job started successfully
            boolean isMachine = requestingMachine != null || src.machine()
                .isPresent();
            IAEMixinCallbacks.getInstance()
                .jobStarted(
                    new AECraftingCPUCluster((CraftingCPUCluster) ((CraftingLinkAccessor) link).callGetCpu()),
                    new AECraftingGrid((CraftingGridCache) (Object) this),
                    new AEGrid(grid),
                    false,
                    !isMachine);
        }
    }

    @Inject(
        method = "recalculateCraftingPatterns",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;clear()V", ordinal = 0, shift = At.Shift.AFTER))
    void ae2webintegration$updatePatternsStart(CallbackInfo ci) {
        CraftingMediumTracker.updatingPatterns((CraftingGridCache) (Object) this, grid);
    }

    @Redirect(
        method = "recalculateCraftingPatterns",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;provideCrafting(Lappeng/api/networking/crafting/ICraftingProviderHelper;)V"))
    void ae2webintegration$provideCrafting(ICraftingProvider instance,
        ICraftingProviderHelper iCraftingProviderHelper) {
        CraftingMediumTracker.provideCrafting((CraftingGridCache) (Object) this, grid, instance);
        instance.provideCrafting(iCraftingProviderHelper);
    }

    @Inject(method = "addCraftingOption", at = @At("HEAD"))
    void ae2webintegration$addCraftingOption(ICraftingMedium medium, ICraftingPatternDetails api, CallbackInfo ci) {
        CraftingMediumTracker.addCraftingOption((CraftingGridCache) (Object) this, grid, medium);
    }

    @Inject(method = "recalculateCraftingPatterns", at = @At(value = "TAIL"))
    void ae2webintegration$updatePatternsEnd(CallbackInfo ci) {
        CraftingMediumTracker.doneUpdatingPatterns((CraftingGridCache) (Object) this, grid);
    }

}
