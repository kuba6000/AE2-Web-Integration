package pl.kuba6000.ae2webintegration.core.interfaces;

import java.util.Map;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;

public interface ICraftingMediumTracker {

    Map<ICraftingProvider, IGridNode> web$getCraftingMediums();

}
