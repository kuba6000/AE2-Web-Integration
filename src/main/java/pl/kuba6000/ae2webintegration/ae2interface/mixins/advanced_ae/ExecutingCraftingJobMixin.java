package pl.kuba6000.ae2webintegration.ae2interface.mixins.advanced_ae;

import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.crafting.inv.ListCraftingInventory;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IExecutingCraftingJobAccessor;

@Mixin(value = ExecutingCraftingJob.class, remap = false)
public class ExecutingCraftingJobMixin implements IExecutingCraftingJobAccessor {

    @Shadow
    @Final
    ListCraftingInventory waitingFor;

    @Override
    public ListCraftingInventory web$getWaitingFor() {
        return waitingFor;
    }

}
