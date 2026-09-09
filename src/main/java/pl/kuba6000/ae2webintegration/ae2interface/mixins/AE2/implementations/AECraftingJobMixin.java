package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.me.Grid;
import appeng.menu.me.crafting.CraftingPlanSummary;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPlayerSource;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;

@Mixin(value = ICraftingPlan.class, remap = false)
public interface AECraftingJobMixin extends IAECraftingJob {

    @Override
    default boolean web$isSimulation() {
        return ((ICraftingPlan) (Object) this).simulation();
    }

    @Override
    default long web$getByteTotal() {
        return ((ICraftingPlan) (Object) this).bytes();
    }

    @Override
    default ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
        return (ICraftingPlanSummary) CraftingPlanSummary
            .fromJob((Grid) grid, ((IGridPlayerSource) grid).web$getPlayerSource(), (ICraftingPlan) (Object) this);
    }
}
