package com.kuba6000.ae2webintegration.mixins.AE2;

import net.minecraft.inventory.InventoryCrafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kuba6000.ae2webintegration.AE2JobTracker;
import com.llamalad7.mixinextras.sugar.Local;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public class CraftingCPUClusterMixin {

    @Shadow
    private void postCraftingStatusChange(final IAEItemStack diff) {}

    @Inject(method = "postCraftingStatusChange", at = @At("HEAD"))
    void ae2webintegration$postCraftingStatusChange(IAEItemStack diff, CallbackInfo ci) {
        AE2JobTracker.updateCraftingStatus((ICraftingCPU) this, diff);
    }

    @Inject(method = "completeJob", at = @At("HEAD"))
    void ae2webintegration$completeJob(CallbackInfo ci) {
        AE2JobTracker.completeCrafting((ICraftingCPU) this);
    }

    @Inject(method = "cancel", at = @At("HEAD"))
    void ae2webintegration$cancel(CallbackInfo ci) {
        AE2JobTracker.cancelCrafting((ICraftingCPU) this);
    }

    // SEEMS TO BE FIXED IN 1.12.2
//    @Inject(
//        method = "injectItems",
//        at = @At(
//            value = "INVOKE",
//            target = "Lappeng/api/storage/data/IAEItemStack;setStackSize(J)Lappeng/api/storage/data/IAEStack;",
//            shift = At.Shift.AFTER,
//            ordinal = 2))
//    void ae2webintegration$fixCpuCluster(CallbackInfoReturnable<IAEStack> cir, @Local(ordinal = 1) IAEItemStack is) {
//        postCraftingStatusChange(is);
//    }

    @Redirect(
        method = "executeCrafting",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingMedium;pushPattern(Lappeng/api/networking/crafting/ICraftingPatternDetails;Lnet/minecraft/inventory/InventoryCrafting;)Z"))
    private boolean ae2webintegration$pushPattern(ICraftingMedium medium, ICraftingPatternDetails details,
        InventoryCrafting ic) {
        if (medium.pushPattern(details, ic)) {
            AE2JobTracker.pushedPattern((CraftingCPUCluster) (Object) this, medium, details);
            return true;
        }
        return false;
    }

}
