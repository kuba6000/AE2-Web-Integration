package com.kuba6000.ae2webintegration.core.interfaces.service;

import java.util.Set;
import java.util.concurrent.Future;

import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumTracker;

public interface IAECraftingGrid {

    ICraftingMediumTracker web$getCraftingProviders();

    int web$getCPUCount();

    Set<ICraftingCPUCluster> web$getCPUs();

    Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey stack, long quantity);

    String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower, IAEGrid grid);
}
