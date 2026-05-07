package pl.kuba6000.ae2webintegration.core.api;

import java.util.Objects;


public class DimensionalCoords {

    int dimid;
    int x;
    int y;
    int z;

    public DimensionalCoords(int dimid, int x, int y, int z) {
        this.dimid = dimid;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimid, x, y, z);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DimensionalCoords)) return false;
        DimensionalCoords coords = (DimensionalCoords) obj;
        return coords.dimid == dimid
            && coords.x == x
            && coords.y == y
            && coords.z == z;
    }
}
