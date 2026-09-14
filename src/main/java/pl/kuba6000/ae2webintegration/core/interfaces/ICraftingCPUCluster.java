package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

public interface ICraftingCPUCluster {

    /** Current native grid, or null for a detached CPU; queried only for deferred lifecycle binding. */
    @Nullable
    IAEGrid web$getGrid();

    /** Stable address within a saved world, independent of display name and current crafting job. */
    @NotNull
    StableKey web$getKey();

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
