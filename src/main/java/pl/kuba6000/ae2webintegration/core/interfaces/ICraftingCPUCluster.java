package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.NotNull;

public interface ICraftingCPUCluster {

    /** Stable address within a saved world, independent of display name and current crafting job. */
    @NotNull
    String web$getId();

    void web$setInternalID(int id);

    boolean web$hasCustomName();

    String web$getName();

    long web$getAvailableStorage();

    long web$getUsedStorage();

    long web$getCoProcessors();

    boolean web$isBusy();

    void web$cancel();

    IAEGenericStack web$getFinalOutput();

    long web$getActiveItems(IAEKey key);

    long web$getPendingItems(IAEKey key);

    long web$getStorageItems(IAEKey key);

    void web$getAllItems(IStackList list);

    IStackList web$getWaitingFor();

}
