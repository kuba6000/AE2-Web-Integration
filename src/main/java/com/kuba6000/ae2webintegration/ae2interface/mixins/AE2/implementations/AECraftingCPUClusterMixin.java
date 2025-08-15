package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;

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
    public IItemStack web$getFinalOutput() {
        return (IItemStack) ((CraftingCPUCluster) (Object) this).getFinalOutput();
    }

    @Override
    public void web$getActiveItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this)
            .getListOfItem((appeng.api.storage.data.IItemList<IAEItemStack>) (Object) list, CraftingItemList.ACTIVE);
    }

    @Override
    public void web$getPendingItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this)
            .getListOfItem((appeng.api.storage.data.IItemList<IAEItemStack>) (Object) list, CraftingItemList.PENDING);
    }

    @Override
    public void web$getStorageItems(IItemList list) {
        ((CraftingCPUCluster) (Object) this)
            .getListOfItem((appeng.api.storage.data.IItemList<IAEItemStack>) (Object) list, CraftingItemList.STORAGE);
    }

    @Override
    public IItemList web$getWaitingFor() {
        return (IItemList) (Object) waitingFor;
    }
}
