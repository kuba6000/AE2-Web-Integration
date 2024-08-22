package com.kuba6000.ae2webintegration.mixins.AE2.MergeDisabler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import appeng.api.storage.data.IAEItemStack;
import appeng.me.cache.CraftingGridCache;

@Mixin(value = CraftingGridCache.class, remap = false)
public class CraftingGridCacheMixin {

    @ModifyArg(
        method = "submitJob",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/storage/data/IAEItemStack;isSameType(Lappeng/api/storage/data/IAEItemStack;)Z"))
    IAEItemStack ae2webintegration$returnFalse(IAEItemStack original) {
        return null;
    }

}
