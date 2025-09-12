package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.BaseActionSource;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.ae2interface.CraftingMediumTracker;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

@Mixin(value = CraftingGridCache.class, remap = false)
public class CraftingGridCacheMixin {

    @Final
    @Shadow
    private IGrid grid;

    @Redirect(
        method = "submitJob(Lappeng/api/networking/crafting/ICraftingJob;Lappeng/api/networking/crafting/ICraftingRequester;Lappeng/api/networking/crafting/ICraftingCPU;ZLappeng/api/networking/security/BaseActionSource;Z)Lappeng/api/networking/crafting/ICraftingLink;",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/me/cluster/implementations/CraftingCPUCluster;submitJob(Lappeng/api/networking/IGrid;Lappeng/api/networking/crafting/ICraftingJob;Lappeng/api/networking/security/BaseActionSource;Lappeng/api/networking/crafting/ICraftingRequester;)Lappeng/api/networking/crafting/ICraftingLink;"))
    ICraftingLink ae2webintegration$submitJob(CraftingCPUCluster instance, IGrid craftID, ICraftingJob whatLink,
        BaseActionSource list, ICraftingRequester e) {
        boolean isMerging = false;
        if (instance.isBusy()) {
            isMerging = true;
        }
        ICraftingLink link = instance.submitJob(craftID, whatLink, list, e);
        if (link != null) { // job started successfully
            boolean isMachine = e != null || list.isMachine();
            IAEMixinCallbacks.getInstance()
                .jobStarted(
                    (ICraftingCPUCluster) (Object) instance,
                    (IAECraftingGrid) this,
                    (IAEGrid) grid,
                    isMerging,
                    !isMachine);
        }
        return link;
    }

    @Inject(
        method = "updatePatterns",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V", ordinal = 0, shift = At.Shift.AFTER))
    void ae2webintegration$updatePatternsStart(CallbackInfo ci) {
        CraftingMediumTracker.updatingPatterns((CraftingGridCache) (Object) this, grid);
    }

    @Redirect(
        method = "updatePatterns",
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

    @Inject(method = "updatePatterns", at = @At(value = "TAIL"))
    void ae2webintegration$updatePatternsEnd(CallbackInfo ci) {
        CraftingMediumTracker.doneUpdatingPatterns((CraftingGridCache) (Object) this, grid);
    }

}
