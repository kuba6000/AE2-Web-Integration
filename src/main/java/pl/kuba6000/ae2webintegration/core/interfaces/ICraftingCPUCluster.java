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

    default void web$getActiveItems(IItemList list) {
        throw new UnsupportedOperationException("Use web$getActiveItems(IAEKey) on this version");
    }

    long web$getActiveItems(IAEKey key);

    default void web$getPendingItems(IItemList list) {
        throw new UnsupportedOperationException("Use web$getPendingItems(IAEKey) on this version");
    }

    long web$getPendingItems(IAEKey key);

    default void web$getStorageItems(IItemList list) {
        throw new UnsupportedOperationException("Use web$getStorageItems(IAEKey) on this version");
    }

    long web$getStorageItems(IAEKey key);

    void web$getAllItems(IItemList list);

    IItemList web$getWaitingFor();

}
