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

    IAEGenericStack web$getFinalOutput();

    void web$getAllItems(IItemList list);

    long web$getActiveItems(IAEKey key);

    long web$getPendingItems(IAEKey key);

    long web$getStorageItems(IAEKey key);

    IItemList web$getWaitingFor();

}
