package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import net.minecraft.item.Item;

import com.glodblock.github.common.item.ItemFluidDrop;

/** Keeps optional AE2FC class linkage behind the caller's mod-presence check. */
public final class FluidCraftResources {

    private FluidCraftResources() {}

    public static boolean isFluidDrop(Item item) {
        return item instanceof ItemFluidDrop;
    }
}
