package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class AECraftingCPUClusterMixin implements ICraftingCPUCluster {

    @Shadow
    private appeng.api.storage.data.IItemList<IAEItemStack> waitingFor;

    @Unique
    private int web$internalID = -1;

    @Override
    public void web$setInternalID(int id) {
        web$internalID = id;
    }

    @Override
    public boolean web$hasCustomName() {
        return !((CraftingCPUCluster) (Object) this).getName()
            .isEmpty();
    }

    @Override
    public String web$getName() {
        return web$hasCustomName() ? ((CraftingCPUCluster) (Object) this).getName() : ("CPU #" + web$internalID);
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
        if (!web$usedStorageInitialized) {
            web$usedStorageInitialized = true;
            try {
                appeng.me.cluster.implementations.CraftingCPUCluster.class.getDeclaredMethod("getUsedStorage");
            } catch (NoSuchMethodException e) {
                web$isUsedStorageAvailable = false;
                return -1L;
            }
        }
        if (!web$isUsedStorageAvailable) return -1L;
        return ((CraftingCPUCluster) (Object) this).getUsedStorage();
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
        ((CraftingCPUCluster) (Object) this).cancel();
    }

    @Override
    public IStack web$getFinalOutput() {
        return (IStack) ((CraftingCPUCluster) (Object) this).getFinalMultiOutput();
    }

    @Override
    public void web$getActiveItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this).getModernListOfItem(
            (appeng.api.storage.data.IItemList<IAEStack<?>>) (Object) list,
            CraftingItemList.ACTIVE);
    }

    @Override
    public void web$getPendingItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this).getModernListOfItem(
            (appeng.api.storage.data.IItemList<IAEStack<?>>) (Object) list,
            CraftingItemList.PENDING);
    }

    @Override
    public void web$getStorageItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this).getModernListOfItem(
            (appeng.api.storage.data.IItemList<IAEStack<?>>) (Object) list,
            CraftingItemList.STORAGE);
    }

    @Override
    public IItemList web$getWaitingFor() {
        return (IItemList) (Object) waitingFor;
    }
}
