package com.kuba6000.ae2webintegration.mixins.AE2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cluster.implementations.CraftingCPUCluster;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public interface CraftingCPUClusterAccessor {

    @Accessor
    IItemList<IAEItemStack> getWaitingFor();

}
