package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import net.minecraft.world.World;

public interface PlayerSourceLifecycle {

    /** Clears the cached source for one world, or every source when {@code world} is {@code null}. */
    void web$clearPlayerSource(World world);
}
