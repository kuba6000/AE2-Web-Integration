package pl.kuba6000.ae2webintegration.core.interfaces.service;

import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Function;


import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumTracker;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

public interface IAECraftingGrid {

    int web$getCPUCount();

    Set<ICraftingCPUCluster> web$getCPUs();

    Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IStack stack);

    Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey stack, long amount);

    String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower, IAEGrid grid);

    ICraftingMediumTracker web$getCraftingProviders();

    Set<IAEKey> web$getCraftables(Function<IAEKey, Boolean> filter);
}
