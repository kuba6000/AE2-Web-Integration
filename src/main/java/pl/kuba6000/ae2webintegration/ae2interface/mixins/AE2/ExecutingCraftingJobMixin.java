package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.crafting.execution.ExecutingCraftingJob;
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
