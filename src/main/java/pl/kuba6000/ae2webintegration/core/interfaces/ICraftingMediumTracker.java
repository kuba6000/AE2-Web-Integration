package pl.kuba6000.ae2webintegration.core.interfaces;

import java.util.Map;

/**
 * Tracks crafting medium ↔ viewable associations.
 * Implemented by the ae2interface layer with actual AE2 types (ICraftingProvider, IGridNode).
 * Core consumers treat the map entries as opaque Objects.
 */
public interface ICraftingMediumTracker {

    Map<Object, Object> web$getCraftingMediums();

}
