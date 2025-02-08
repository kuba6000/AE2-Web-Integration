package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.Map;

import net.minecraft.inventory.InventoryCrafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuba6000.ae2webintegration.ae2interface.CraftingMediumTracker;
import com.kuba6000.ae2webintegration.ae2interface.implementations.AECraftingCPUCluster;
import com.kuba6000.ae2webintegration.ae2interface.implementations.AECraftingPatternDetails;
import com.kuba6000.ae2webintegration.ae2interface.implementations.ItemStack;
import com.kuba6000.ae2webintegration.ae2interface.implementations.PatternProviderViewable;
import com.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.helpers.IInterfaceHost;
import appeng.me.cluster.implementations.CraftingCPUCluster;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public class CraftingCPUClusterMixin {

    @Shadow
    private void postCraftingStatusChange(final IAEItemStack diff) {}

    @Shadow
    private IGrid getGrid() {
        throw new IllegalStateException("Mixin failed to apply");
    }

    @Inject(method = "postCraftingStatusChange", at = @At("HEAD"))
    void ae2webintegration$postCraftingStatusChange(IAEItemStack diff, CallbackInfo ci) {
        IAEMixinCallbacks.getInstance()
            .craftingStatusPostedUpdate(
                new AECraftingCPUCluster((CraftingCPUCluster) (Object) this),
                new ItemStack(diff));
    }

    @Inject(method = "completeJob", at = @At("HEAD"))
    void ae2webintegration$completeJob(CallbackInfo ci) {
        IAEMixinCallbacks.getInstance()
            .jobCompleted(new AECraftingCPUCluster((CraftingCPUCluster) (Object) this));
    }

    @Inject(method = "cancel", at = @At("HEAD"))
    void ae2webintegration$cancel(CallbackInfo ci) {
        IAEMixinCallbacks.getInstance()
            .jobCancelled(new AECraftingCPUCluster((CraftingCPUCluster) (Object) this));
    }

    // SEEMS TO BE FIXED IN 1.12.2
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
            target = "Lappeng/api/networking/crafting/ICraftingMedium;pushPattern(Lappeng/api/networking/crafting/ICraftingPatternDetails;Lnet/minecraft/inventory/InventoryCrafting;)Z"))
    private boolean ae2webintegration$pushPattern(ICraftingMedium medium, ICraftingPatternDetails details,
        InventoryCrafting ic) {
        if (medium.pushPattern(details, ic)) {
            IInterfaceHost viewable = null;
            Map<ICraftingMedium, IInterfaceHost> mediumToViewable = CraftingMediumTracker.mediumToViewable
                .get(getGrid());
            if (mediumToViewable != null) {
                viewable = mediumToViewable.get(medium);
            }
            IAEMixinCallbacks.getInstance()
                .pushedPattern(
                    new AECraftingCPUCluster((CraftingCPUCluster) (Object) this),
                    viewable != null ? new PatternProviderViewable(viewable) : null,
                    new AECraftingPatternDetails(details));
            return true;
        }
        return false;
    }

}
