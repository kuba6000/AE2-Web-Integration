package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import appeng.api.networking.crafting.ICraftingMedium;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

public interface ICraftingMediumTracker {

    IPatternProviderViewable web$getViewableForCraftingMedium(ICraftingMedium medium);

}
