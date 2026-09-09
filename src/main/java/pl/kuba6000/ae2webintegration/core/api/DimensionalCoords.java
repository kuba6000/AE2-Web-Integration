package pl.kuba6000.ae2webintegration.core.api;

import java.util.Objects;

public class DimensionalCoords {

    String dimid;
    int x;
    int y;
    int z;

    public DimensionalCoords(int dimid, int x, int y, int z) {
        this(String.valueOf(dimid), x, y, z);
    }

    public DimensionalCoords(String dimid, int x, int y, int z) {
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
        if (!(obj instanceof DimensionalCoords coords)) return false;
        return Objects.equals(coords.dimid, dimid) && coords.x == x && coords.y == y && coords.z == z;
    }
}
