package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.AEApi;
import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;

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

    @Unique
    private Method web$getUsedStorageMethod = null;

    @Override
    public long web$getUsedStorage() {
        if (!web$usedStorageInitialized) {
            web$usedStorageInitialized = true;
            try {
                web$getUsedStorageMethod = CraftingCPUCluster.class.getDeclaredMethod("getUsedStorage");
            } catch (NoSuchMethodException e) {
                web$isUsedStorageAvailable = false;
                return -1L;
            }
        }
        if (!web$isUsedStorageAvailable) return -1L;
        try {
            return (long) web$getUsedStorageMethod.invoke(this);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return -1L;
        }
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
    public IAEGenericStack web$getFinalOutput() {
        return (IAEGenericStack) ((CraftingCPUCluster) (Object) this).getFinalOutput();
    }

    @Override
    public void web$getActiveItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this)
            .getListOfItem((appeng.api.storage.data.IItemList<IAEItemStack>) (Object) list, CraftingItemList.ACTIVE);
    }

    @Override
    public long web$getActiveItems(IAEKey key) {
        IItemList list = web$getItemsFor(CraftingItemList.ACTIVE);
        return list.web$findPrecise(key);
    }

    @Override
    public void web$getPendingItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this)
            .getListOfItem((appeng.api.storage.data.IItemList<IAEItemStack>) (Object) list, CraftingItemList.PENDING);
    }

    @Override
    public long web$getPendingItems(IAEKey key) {
        IItemList list = web$getItemsFor(CraftingItemList.PENDING);
        return list.web$findPrecise(key);
    }

    @Override
    public void web$getStorageItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this)
            .getListOfItem((appeng.api.storage.data.IItemList<IAEItemStack>) (Object) list, CraftingItemList.STORAGE);
    }

    @Override
    public long web$getStorageItems(IAEKey key) {
        IItemList list = web$getItemsFor(CraftingItemList.STORAGE);
        return list.web$findPrecise(key);
    }

    @Unique
    private IItemList web$getItemsFor(CraftingItemList which) {
        appeng.api.storage.data.IItemList<IAEItemStack> internal = AEApi.instance()
            .storage()
            .getStorageChannel(IItemStorageChannel.class)
            .createList();
        ((CraftingCPUCluster) (Object) this).getListOfItem(internal, which);
        return (IItemList) (Object) internal;
    }

    @Override
    public void web$getAllItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this)
            .getListOfItem((appeng.api.storage.data.IItemList<IAEItemStack>) (Object) list, CraftingItemList.ALL);
    }

    @Override
    public IItemList web$getWaitingFor() {
        return (IItemList) (Object) waitingFor;
    }
}
