package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.me.Grid;
import appeng.me.helpers.PlayerSource;
import appeng.menu.me.crafting.CraftingPlanSummary;

@Mixin(value = ICraftingPlan.class, remap = false)
public interface AECraftingJobMixin extends IAECraftingJob {

    @Override
    public default boolean web$isSimulation() {
        return ((ICraftingPlan) (Object) this).simulation();
    }

    @Override
    public default long web$getByteTotal() {
        return ((ICraftingPlan) (Object) this).bytes();
    }

    @Override
    public default ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
        return (ICraftingPlanSummary) CraftingPlanSummary
            .fromJob((Grid) grid, (PlayerSource) grid.web$getPlayerSource(), (ICraftingPlan) (Object) this);
    }
}
