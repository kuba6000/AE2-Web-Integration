package pl.kuba6000.ae2webintegration.core.interfaces;

public interface ICraftingCPUCluster {

    void web$setInternalID(int id);

    boolean web$hasCustomName();

    String web$getName();

    long web$getAvailableStorage();

    long web$getUsedStorage();

    long web$getCoProcessors();

    boolean web$isBusy();

    void web$cancel();

    IStack web$getFinalOutput();

    void web$getActiveItems(IItemList list);

    void web$getPendingItems(IItemList list);

    void web$getStorageItems(IItemList list);

    IItemList web$getWaitingFor();

}
