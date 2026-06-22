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
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class AECraftingCPUClusterMixin implements ICraftingCPUCluster {

    @Shadow
    private appeng.api.storage.data.IItemList<IAEItemStack> waitingFor;

    @Unique
    private int web$internalID = -1;

    @Unique
    private boolean web$isUsedStorageAvailable = true;

    @Unique
    private boolean web$usedStorageInitialized = false;

    @Unique
    private Method web$getUsedStorageMethod = null;

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
    public void web$getAllItems(IStackList list) {
        web$populateList(list, CraftingItemList.ACTIVE);
        web$populateList(list, CraftingItemList.PENDING);
        web$populateList(list, CraftingItemList.STORAGE);
    }

    @Override
    public IStackList web$getWaitingFor() {
        return (IStackList) (Object) waitingFor;
    }

    @Override
    public long web$getActiveItems(IAEKey key) {
        return web$findAmount(key, CraftingItemList.ACTIVE);
    }

    @Override
    public long web$getPendingItems(IAEKey key) {
        return web$findAmount(key, CraftingItemList.PENDING);
    }

    @Override
    public long web$getStorageItems(IAEKey key) {
        return web$findAmount(key, CraftingItemList.STORAGE);
    }

    @Unique
    private long web$findAmount(IAEKey key, CraftingItemList which) {
        IStackList list = web$createList();
        web$populateList(list, which);
        return list.web$getAmount(key);
    }

    @Unique
    private void web$populateList(IStackList list, CraftingItemList which) {
        ((CraftingCPUCluster) (Object) this).getListOfItem(
            (appeng.api.storage.data.IItemList<IAEItemStack>) (Object) list,
            which);
    }

    @Unique
    private IStackList web$createList() {
        return (IStackList) (Object) AEApi.instance()
            .storage()
            .getStorageChannel(IItemStorageChannel.class)
            .createList();
    }
}
