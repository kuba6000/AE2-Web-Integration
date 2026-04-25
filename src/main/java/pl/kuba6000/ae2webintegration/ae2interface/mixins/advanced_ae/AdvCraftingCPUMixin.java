package pl.kuba6000.ae2webintegration.ae2interface.mixins.advanced_ae;

import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;

@Mixin(value = AdvCraftingCPU.class, remap = false)
public class AdvCraftingCPUMixin implements ICraftingCPUCluster {

    @Override
    public void web$setInternalID(int id) {
        AE.cpuInternalIDMap.put(this, id);
    }

    @Override
    public boolean web$hasCustomName() {
        return !(((ICraftingCPU) this).getName() == null);
    }

    @Override
    public String web$getName() {
        return web$hasCustomName() ? ((ICraftingCPU) this).getName()
            .getString() : ("CPU #" + AE.cpuInternalIDMap.getOrDefault(this, -1));
    }

    @Override
    public long web$getAvailableStorage() {
        return ((ICraftingCPU) this).getAvailableStorage();
    }

    @Override
    public long web$getUsedStorage() {
        return -1L;
    }

    @Override
    public long web$getCoProcessors() {
        return ((ICraftingCPU) this).getCoProcessors();
    }

    @Override
    public boolean web$isBusy() {
        return ((ICraftingCPU) this).isBusy();
    }

    @Override
    public void web$cancel() {
        ((AdvCraftingCPU) (Object) this).cancelJob();
    }

    @Override
    public IAEGenericStack web$getFinalOutput() {
        if (web$isBusy()) return (IAEGenericStack) (Object) ((ICraftingCPU) this).getJobStatus()
            .crafting();
        return null;
    }

    @Override
    public void web$getAllItems(IItemList list) {
        ((AdvCraftingCPU) (Object) this).craftingLogic.getAllItems((KeyCounter) (Object) list);
    }

    @Override
    public long web$getActiveItems(IAEKey key) {
        return ((AdvCraftingCPU) (Object) this).craftingLogic.getWaitingFor((AEKey) key);
    }

    @Override
    public long web$getPendingItems(IAEKey key) {
        return ((AdvCraftingCPU) (Object) this).craftingLogic.getWaitingFor((AEKey) key);
    }

    @Override
    public long web$getStorageItems(IAEKey key) {
        return ((AdvCraftingCPU) (Object) this).craftingLogic.getWaitingFor((AEKey) key);
    }

    @Override
    public IItemList web$getWaitingFor() {
        return (IItemList) (Object) ((ICraftingCPULogicAccessor) ((AdvCraftingCPU) (Object) this).craftingLogic)
            .web$getJob()
            .web$getWaitingFor().list;
    }
}
