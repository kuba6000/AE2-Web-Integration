package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;

/** Submit to this exact cluster with its own machine source; an AdvancedAE free slot belongs to its cluster. */
public interface IMachineOrderCpu {

    ICraftingSubmitResult web$submitReserved(IGrid grid, ICraftingPlan plan);
}
