package com.kuba6000.ae2webintegration.core.interfaces;

public interface ICraftingCPUCluster {

    boolean hasCustomName();

    String getName();

    long getAvailableStorage();

    long getUsedStorage();

    long getCoProcessors();

    boolean isBusy();

    void cancel();

    IItemStack getFinalOutput();

    void getActiveItems(IItemList list);

    void getPendingItems(IItemList list);

    void getStorageItems(IItemList list);

    IItemList getWaitingFor();

}
