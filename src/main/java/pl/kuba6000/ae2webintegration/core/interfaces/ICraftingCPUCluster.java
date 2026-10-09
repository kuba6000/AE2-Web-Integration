package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

public interface ICraftingCPUCluster extends IIdentityHolder {

    /** Stable address within a saved world, independent of display name and current crafting job. */
    @NotNull
    StableKey web$getKey();

    String web$getName();

    long web$getAvailableStorage();

    long web$getUsedStorage();

    long web$getCoProcessors();

    boolean web$isBusy();

    /**
     * Whether current native state admits a player request, before checking its output and storage needs.
     * Read on the server thread; native submission remains authoritative.
     */
    boolean web$acceptsPlayerJobs();

    /** Native automatic-selection policy; null when unsupported or unknown. Read on the server thread. */
    @Nullable
    CpuSelectionMode web$getSelectionMode();

    void web$cancel();

    IAEGenericStack web$getFinalOutput();

    long web$getActiveItems(IAEKey key);

    long web$getPendingItems(IAEKey key);

    long web$getStorageItems(IAEKey key);

    void web$getAllItems(IStackList list);

    IStackList web$getWaitingFor();

}
