package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import appeng.api.networking.crafting.ICraftingProvider;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

public interface ICraftingMediumTracker {

    IPatternProviderViewable web$getViewableForCraftingMedium(ICraftingProvider medium);

}
