package pl.kuba6000.ae2webintegration.core.interfaces.service;

import java.util.Set;
import java.util.concurrent.Future;

import net.minecraft.util.IChatComponent;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

public interface IAECraftingGrid {

    int web$getCPUCount();

    Set<ICraftingCPUCluster> web$getCPUs();

    Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IStack stack);

    IChatComponent web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower, IAEGrid grid);
}
