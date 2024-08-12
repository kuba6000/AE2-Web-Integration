package com.kuba6000.ae2webintegration.mixins.AE2.MergeDisabler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.BaseActionSource;
import appeng.me.cluster.implementations.CraftingCPUCluster;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public class CraftingCPUClusterMixin {

    /**
     * @author kuba6000
     * @reason L
     */
    @Overwrite
    public ICraftingLink mergeJob(final IGrid g, final ICraftingJob job, final BaseActionSource src) {
        return null;
    }

}
