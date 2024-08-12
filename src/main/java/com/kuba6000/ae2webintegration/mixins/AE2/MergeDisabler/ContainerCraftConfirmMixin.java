package com.kuba6000.ae2webintegration.mixins.AE2.MergeDisabler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.container.implementations.ContainerCraftConfirm;
import appeng.container.implementations.CraftingCPUStatus;

@Mixin(value = ContainerCraftConfirm.class, remap = false)
public class ContainerCraftConfirmMixin {

    @Inject(method = "cpuCraftingSameItem", at = @At("HEAD"), cancellable = true)
    void ae2webinterface$cpuCraftingSameItem(CraftingCPUStatus c, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

}
