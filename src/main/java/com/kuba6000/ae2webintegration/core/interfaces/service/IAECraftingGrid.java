package com.kuba6000.ae2webintegration.core.interfaces.service;

import java.util.Set;
import java.util.concurrent.Future;

import net.minecraft.util.IChatComponent;

import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

public interface IAECraftingGrid {

    int getCPUCount();

    Set<ICraftingCPUCluster> getCPUs();

    Future<IAECraftingJob> beginCraftingJob(IAEGrid grid, IItemStack stack);

    IChatComponent submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower, IAEGrid grid);
}
