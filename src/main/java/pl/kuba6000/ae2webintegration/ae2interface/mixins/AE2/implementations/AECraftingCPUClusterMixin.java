package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class AECraftingCPUClusterMixin implements ICraftingCPUCluster {

    @Unique
    private int web$internalID = -1;

    @Override
    public void web$setInternalID(int id) {
        web$internalID = id;
    }

    @Override
    public boolean web$hasCustomName() {
        return !(((CraftingCPUCluster) (Object) this).getName() == null);
    }

    @Override
    public String web$getName() {
        return web$hasCustomName() ? ((CraftingCPUCluster) (Object) this).getName()
            .getString() : ("CPU #" + web$internalID);
    }

    @Override
    public long web$getAvailableStorage() {
        return ((CraftingCPUCluster) (Object) this).getAvailableStorage();
    }

    @Unique
    private boolean web$isUsedStorageAvailable = true;

    @Unique
    private boolean web$usedStorageInitialized = false;

    @Override
    public long web$getUsedStorage() {
        return -1L;
        // if (!web$usedStorageInitialized) {
        // web$usedStorageInitialized = true;
        // try {
        // appeng.me.cluster.implementations.CraftingCPUCluster.class.getDeclaredMethod("getUsedStorage");
        // } catch (NoSuchMethodException e) {
        // web$isUsedStorageAvailable = false;
        // return -1L;
        // }
        // }
        // if (!web$isUsedStorageAvailable) return -1L;
        // return ((CraftingCPUCluster) (Object) this).getUsedStorage();
    }

    @Override
    public long web$getCoProcessors() {
        return ((CraftingCPUCluster) (Object) this).getCoProcessors();
    }

    @Override
    public boolean web$isBusy() {
        return ((CraftingCPUCluster) (Object) this).isBusy();
    }

    @Override
    public void web$cancel() {
        ((CraftingCPUCluster) (Object) this).cancelJob();
    }

    @Override
    public IAEGenericStack web$getFinalOutput() {
        return (IAEGenericStack) (Object) ((CraftingCPUCluster) (Object) this).craftingLogic.getFinalJobOutput();
    }

    @Override
    public void web$getAllItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this).craftingLogic.getAllItems((KeyCounter) (Object) list);
    }

    @Override
    public long web$getActiveItems(IAEKey key) {
        return ((CraftingCPUCluster) (Object) this).craftingLogic.getWaitingFor((AEKey) key);
    }

    @Override
    public long web$getPendingItems(IAEKey key) {
        return ((CraftingCPUCluster) (Object) this).craftingLogic.getPendingOutputs((AEKey) key);
    }

    @Override
    public long web$getStorageItems(IAEKey key) {
        return ((CraftingCPUCluster) (Object) this).craftingLogic.getStored((AEKey) key);
    }

    @Override
    public IItemList web$getWaitingFor() {
        return (IItemList) (Object) ((ICraftingCPULogicAccessor) ((CraftingCPUCluster) (Object) this).craftingLogic)
            .web$getJob()
            .web$getWaitingFor().list;
    }
}
