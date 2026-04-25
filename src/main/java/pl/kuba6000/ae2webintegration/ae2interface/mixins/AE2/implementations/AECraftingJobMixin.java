package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;

@Mixin(value = ICraftingJob.class, remap = false)
public interface AECraftingJobMixin extends IAECraftingJob {

    @Override
    public default boolean web$isSimulation() {
        return ((ICraftingJob) (Object) this).isSimulation();
    }

    @Override
    public default long web$getByteTotal() {
        return ((ICraftingJob) (Object) this).getByteTotal();
    }

    @Override
    public default void web$populatePlan(IItemList plan) {
        ((ICraftingJob) (Object) this).populatePlan((appeng.api.storage.data.IItemList<IAEStack<?>>) (Object) plan);
    }
}
