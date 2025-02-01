package com.kuba6000.ae2webintegration.ae2interface;

import java.util.IdentityHashMap;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.util.IInterfaceViewable;
import appeng.me.cache.CraftingGridCache;

public class CraftingMediumTracker {

    public static final IdentityHashMap<IGrid, IdentityHashMap<ICraftingMedium, IInterfaceViewable>> mediumToViewable = new IdentityHashMap<>();
    private static boolean isUpdatingPatterns = false;
    private static ICraftingProvider currentCraftingProvider = null;

    public static void updatingPatterns(CraftingGridCache craftingGrid, IGrid grid) {
        mediumToViewable.put(grid, new IdentityHashMap<>());
        isUpdatingPatterns = true;
    }

    public static void provideCrafting(CraftingGridCache craftingGrid, IGrid grid, ICraftingProvider provider) {
        if (!isUpdatingPatterns) return;
        currentCraftingProvider = provider;
    }

    public static void addCraftingOption(CraftingGridCache craftingGrid, IGrid grid, ICraftingMedium medium) {
        if (!isUpdatingPatterns) return;
        if (currentCraftingProvider == null) return;
        if (currentCraftingProvider instanceof IInterfaceViewable viewable && !mediumToViewable.get(grid)
            .containsKey(medium)) mediumToViewable.get(grid)
                .put(medium, viewable);
    }

    public static void doneUpdatingPatterns(CraftingGridCache craftingGrid, IGrid grid) {
        isUpdatingPatterns = false;
    }

}
