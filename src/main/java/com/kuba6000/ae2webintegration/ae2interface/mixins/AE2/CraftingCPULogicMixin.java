package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import com.kuba6000.ae2webintegration.ae2interface.accessors.IExecutingCraftingJobAccessor;
import com.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public class CraftingCPULogicMixin implements ICraftingCPULogicAccessor {

    @Final
    @Shadow
    CraftingCPUCluster cluster;

    // @Shadow
    // private void postCraftingStatusChange(final IAEItemStack diff) {
    // throw new IllegalStateException("Mixin failed to apply");
    // }

    @Shadow
    private ExecutingCraftingJob job;

    @Inject(method = "trySubmitJob", at = @At("RETURN"))
    void ae2webintegration$onJobSubmit(IGrid grid, ICraftingPlan plan, IActionSource src,
        @Nullable ICraftingRequester requester, CallbackInfoReturnable<ICraftingSubmitResult> ci) {
        if (ci.getReturnValue()
            .successful()) {
            boolean isMachine = !src.player()
                .isPresent();
            IAEMixinCallbacks.getInstance()
                .jobStarted(
                    (ICraftingCPUCluster) (Object) cluster,
                    (IAECraftingGrid) grid.getCraftingService(),
                    (IAEGrid) grid,
                    false,
                    !isMachine);
        }
    }

    @Inject(method = "postChange", at = @At("HEAD"))
    void ae2webintegration$postCraftingStatusChange(AEKey diff, CallbackInfo ci) {
        IAEMixinCallbacks.getInstance()
            .craftingStatusPostedUpdate((ICraftingCPUCluster) (Object) cluster, (IAEKey) diff);
    }

    @Inject(method = "finishJob", at = @At("HEAD"))
    void ae2webintegration$finishJob(boolean success, CallbackInfo ci) {
        if (success) IAEMixinCallbacks.getInstance()
            .jobCompleted((IAEGrid) cluster.getGrid(), (ICraftingCPUCluster) (Object) cluster);
        else IAEMixinCallbacks.getInstance()
            .jobCancelled((IAEGrid) cluster.getGrid(), (ICraftingCPUCluster) (Object) cluster);
    }

    // @Inject(method = "cancel", at = @At("HEAD"))
    // void ae2webintegration$cancel(CallbackInfo ci) {
    // IAEMixinCallbacks.getInstance()
    // .jobCancelled((IAEGrid) cluster.getGrid(), (ICraftingCPUCluster) this);
    // }

    // @Inject(
    // method = "injectItems",
    // at = @At(
    // value = "INVOKE",
    // target = "Lappeng/api/storage/data/IAEItemStack;setStackSize(J)Lappeng/api/storage/data/IAEStack;",
    // shift = At.Shift.AFTER,
    // ordinal = 2))
    // void ae2webintegration$fixCpuCluster(CallbackInfoReturnable<IAEStack> cir, @Local(ordinal = 1) IAEItemStack is) {
    // postCraftingStatusChange(is);
    // }

    @Redirect(
        method = "executeCrafting",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean ae2webintegration$pushPattern(ICraftingProvider medium, IPatternDetails details, KeyCounter[] ic) {
        if (medium.pushPattern(details, ic)) {
            IGridNode viewable = null;
            Map<ICraftingProvider, IGridNode> mediumToViewable = ((IAECraftingGrid) cluster.getGrid()
                .getService(ICraftingService.class)).web$getCraftingProviders()
                .web$getCraftingMediums();
            if (mediumToViewable != null) {
                viewable = mediumToViewable.get(medium);
            }
            IAEMixinCallbacks.getInstance()
                .pushedPattern(
                    (ICraftingCPUCluster) (Object) cluster,
                    (IPatternProviderViewable) viewable,
                    (IAECraftingPatternDetails) details);
            return true;
        }
        return false;
    }

    @Override
    public IExecutingCraftingJobAccessor web$getJob() {
        return (IExecutingCraftingJobAccessor) job;
    }
}
