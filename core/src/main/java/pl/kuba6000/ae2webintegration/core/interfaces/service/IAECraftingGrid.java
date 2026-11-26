package pl.kuba6000.ae2webintegration.core.interfaces.service;

import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;

public interface IAECraftingGrid {

    /** Current order-time validation in this service's grid, independent of saved listing flags. */
    boolean web$isCurrentlyCraftable(IAEKey key);

    /**
     * True when a crafting-card level emitter provides this resource, so it is requested by the emitter rather than
     * ordered through a calculation. Server thread only. Reads the grid's current emitter registry for the one key;
     * it does not enumerate stock or craftables.
     */
    boolean web$canEmitFor(IAEKey key);

    int web$getCPUCount();

    Set<ICraftingCPUCluster> web$getCPUs();

    Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey key, long amount,
        @NotNull CraftingOptions options);

    String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower, IAEGrid grid);

    /** Submit using the chosen CPU's native machine source. Never fall back to automatic selection. */
    default String web$submitMachineJob(IAECraftingJob job, ICraftingCPUCluster target, IAEGrid grid) {
        throw new UnsupportedOperationException("Machine-only CPU selection is unavailable");
    }

    Set<IAEKey> web$getCraftables(Function<IAEKey, Boolean> filter);

}
