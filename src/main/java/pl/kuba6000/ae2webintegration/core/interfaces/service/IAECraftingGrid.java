package pl.kuba6000.ae2webintegration.core.interfaces.service;

import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Function;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumTracker;

public interface IAECraftingGrid {

    ICraftingMediumTracker web$getCraftingProviders();

    int web$getCPUCount();

    Set<ICraftingCPUCluster> web$getCPUs();

    Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey stack, long quantity);

    String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower, IAEGrid grid);

    Set<IAEKey> web$getCraftables(Function<IAEKey, Boolean> filter);
}
