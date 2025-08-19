package pl.kuba6000.ae2webintegration.core.api;

import java.util.Objects;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class DimensionalCoords {

    @GSONUtils.SkipGSON
    ResourceKey<Level> dimid_internal;
    String dimid;
    int x;
    int y;
    int z;

    public DimensionalCoords(ResourceKey<Level> dimid, int x, int y, int z) {
        this.dimid_internal = dimid;
        this.dimid = dimid_internal.location()
            .toString();
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public DimensionalCoords(Level world, int x, int y, int z) {
        this.dimid_internal = world.dimension();
        this.dimid = dimid_internal.location()
            .toString();
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimid_internal.registry(), dimid_internal.location(), x, y, z);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DimensionalCoords coords && coords.dimid_internal.equals(dimid_internal)
            && coords.x == x
            && coords.y == y
            && coords.z == z;
    }
}
