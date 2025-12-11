package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;

@Mixin(value = ICraftingCPU.class, remap = false)
public interface AECraftingCPUClusterMixin extends ICraftingCPUCluster {

    @Override
    default public void web$setInternalID(int id) {
        AE.cpuInternalIDMap.put(this, id);
    }

    @Override
    default public boolean web$hasCustomName() {
        return !(((ICraftingCPU) this).getName() == null);
    }

    @Override
    default public String web$getName() {
        return web$hasCustomName() ? ((ICraftingCPU) this).getName()
            .getString() : ("CPU #" + AE.cpuInternalIDMap.getOrDefault(this, -1));
    }

    @Override
    default public long web$getAvailableStorage() {
        return ((ICraftingCPU) this).getAvailableStorage();
    }

    @Override
    default public long web$getUsedStorage() {
        return -1L;
    }

    @Override
    default public long web$getCoProcessors() {
        return ((ICraftingCPU) this).getCoProcessors();
    }

    @Override
    default public boolean web$isBusy() {
        return ((ICraftingCPU) this).isBusy();
    }

    @Override
    default public void web$cancel() {
        ((ICraftingCPU) this).cancelJob();
    }

    @Override
    default public IAEGenericStack web$getFinalOutput() {
        if (web$isBusy()) return (IAEGenericStack) (Object) ((ICraftingCPU) this).getJobStatus()
            .crafting();
        return null;
    }

    @Override
    default public void web$getAllItems(IItemList list) {
        if ((Object) this instanceof CraftingCPUCluster cpuCluster)
            cpuCluster.craftingLogic.getAllItems((KeyCounter) (Object) list);
        else if ((Object) this instanceof AdvCraftingCPU advCpu)
            advCpu.craftingLogic.getAllItems((KeyCounter) (Object) list);
    }

    @Override
    default public long web$getActiveItems(IAEKey key) {
        if ((Object) this instanceof CraftingCPUCluster cpuCluster)
            return cpuCluster.craftingLogic.getWaitingFor((AEKey) key);
        else if ((Object) this instanceof AdvCraftingCPU advCpu) return advCpu.craftingLogic.getWaitingFor((AEKey) key);
        return 0L;
    }

    @Override
    default public long web$getPendingItems(IAEKey key) {
        if ((Object) this instanceof CraftingCPUCluster cpuCluster)
            return cpuCluster.craftingLogic.getPendingOutputs((AEKey) key);
        else if ((Object) this instanceof AdvCraftingCPU advCpu) return advCpu.craftingLogic.getWaitingFor((AEKey) key);
        return 0L;
    }

    @Override
    default public long web$getStorageItems(IAEKey key) {
        if ((Object) this instanceof CraftingCPUCluster cpuCluster)
            return cpuCluster.craftingLogic.getStored((AEKey) key);
        else if ((Object) this instanceof AdvCraftingCPU advCpu) return advCpu.craftingLogic.getWaitingFor((AEKey) key);
        return 0L;
    }

    @Override
    default public IItemList web$getWaitingFor() {
        if ((Object) this instanceof CraftingCPUCluster cpuCluster)
            return (IItemList) (Object) ((ICraftingCPULogicAccessor) cpuCluster.craftingLogic).web$getJob()
                .web$getWaitingFor().list;
        else if ((Object) this instanceof AdvCraftingCPU advCpu)
            return (IItemList) (Object) ((ICraftingCPULogicAccessor) advCpu.craftingLogic).web$getJob()
                .web$getWaitingFor().list;
        return (IItemList) (Object) new KeyCounter();
    }
}
