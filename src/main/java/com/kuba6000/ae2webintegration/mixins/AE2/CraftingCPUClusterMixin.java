package com.kuba6000.ae2webintegration.mixins.AE2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuba6000.ae2webintegration.AE2JobTracker;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public class CraftingCPUClusterMixin {

    @Inject(method = "postCraftingStatusChange", at = @At("HEAD"))
    void ae2webintegration$postCraftingStatusChange(IAEItemStack diff, CallbackInfo ci) {
        AE2JobTracker.updateCraftingStatus((ICraftingCPU) this, diff);
    }

    @Inject(method = "completeJob", at = @At("HEAD"))
    void ae2webinterface$completeJob(CallbackInfo ci) {
        AE2JobTracker.completeCrafting((ICraftingCPU) this);
    }

    @Inject(method = "cancel", at = @At("HEAD"))
    void ae2webinterface$cancel(CallbackInfo ci) {
        AE2JobTracker.cancelCrafting((ICraftingCPU) this);
    }

}
