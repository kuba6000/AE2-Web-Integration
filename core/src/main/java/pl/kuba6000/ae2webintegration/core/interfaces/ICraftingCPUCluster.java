package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

public interface ICraftingCPUCluster extends IIdentityHolder {

    /** Stable address within a saved world, independent of display name and current crafting job. */
    @NotNull
    StableKey web$getKey();

    /**
     * Whether this native CPU is still valid and belongs to this exact grid. Server thread only; must not enumerate
     * resources, tasks or other CPUs.
     */
    boolean web$isValidForGrid(@NotNull IAEGrid grid);

    String web$getName();

    /** Native current-job requester name, or null when unavailable. Server thread only. */
    @Nullable
    default String web$getRequesterName() {
        return null;
    }

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

    /** Fills empty lists with absolute counts, in one native pass per list. Server thread; zero entries omitted. */
    void web$getItemLists(@NotNull IStackList active, @NotNull IStackList pending, @NotNull IStackList stored);

    IStackList web$getWaitingFor();

}
