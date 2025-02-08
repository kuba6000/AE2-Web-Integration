package com.kuba6000.ae2webintegration.ae2interface.implementations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.CraftingCPUClusterAccessor;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

import appeng.api.networking.crafting.CraftingItemList;

public class AECraftingCPUCluster extends IAEObject<appeng.me.cluster.implementations.CraftingCPUCluster>
    implements ICraftingCPUCluster {

    int id;

    public AECraftingCPUCluster(appeng.me.cluster.implementations.CraftingCPUCluster object) {
        super(object);
    }

    public AECraftingCPUCluster(appeng.me.cluster.implementations.CraftingCPUCluster object, int id) {
        super(object);
        this.id = id;
    }

    @Override
    public boolean hasCustomName() {
        return !get().getName()
            .isEmpty();
    }

    @Override
    public String getName() {
        return hasCustomName() ? get().getName() : ("CPU #" + id);
    }

    @Override
    public long getAvailableStorage() {
        return get().getAvailableStorage();
    }

    @Override
    public long getUsedStorage() {
        return getUsedStorageInternal(get());
    }

    @Override
    public long getCoProcessors() {
        return get().getCoProcessors();
    }

    @Override
    public boolean isBusy() {
        return get().isBusy();
    }

    @Override
    public void cancel() {
        get().cancel();
    }

    @Override
    public IItemStack getFinalOutput() {
        return new ItemStack(get().getFinalOutput());
    }

    @Override
    public void getActiveItems(IItemList list) {
        get().getListOfItem(((ItemList) list).get(), CraftingItemList.ACTIVE);
    }

    @Override
    public void getPendingItems(IItemList list) {
        get().getListOfItem(((ItemList) list).get(), CraftingItemList.PENDING);
    }

    @Override
    public void getStorageItems(IItemList list) {
        get().getListOfItem(((ItemList) list).get(), CraftingItemList.STORAGE);
    }

    @Override
    public IItemList getWaitingFor() {
        return new ItemList(((CraftingCPUClusterAccessor) (Object) get()).getWaitingFor());
    }

    private static boolean isUsedStorageAvailable = true;
    private static Method getUsedStorageMethod = null;

    private static long getUsedStorageInternal(appeng.me.cluster.implementations.CraftingCPUCluster cluster) {
        if (!isUsedStorageAvailable) return -1L;
        if (getUsedStorageMethod == null) {
            try {
                getUsedStorageMethod = appeng.me.cluster.implementations.CraftingCPUCluster.class
                    .getDeclaredMethod("getUsedStorage");
            } catch (NoSuchMethodException e) {
                isUsedStorageAvailable = false;
                return -1L;
            }
        }
        try {
            return (long) getUsedStorageMethod.invoke(cluster);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return 0L;
        }
    }
}
