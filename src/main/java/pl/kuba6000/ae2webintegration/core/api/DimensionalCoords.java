package pl.kuba6000.ae2webintegration.core.api;

import java.util.Objects;

import org.jetbrains.annotations.NotNull;

public class DimensionalCoords implements Comparable<DimensionalCoords> {

    private final String dimid;
    private final int x;
    private final int y;
    private final int z;

    public DimensionalCoords(int dimid, int x, int y, int z) {
        this(String.valueOf(dimid), x, y, z);
    }

    public DimensionalCoords(@NotNull String dimid, int x, int y, int z) {
        this.dimid = dimid;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Lexicographic dimension/XYZ order, independent of the native dimension naming scheme. */
    @Override
    public int compareTo(@NotNull DimensionalCoords other) {
        int order = dimid.compareTo(other.dimid);
        if (order != 0) return order;
        order = Integer.compare(x, other.x);
        if (order != 0) return order;
        order = Integer.compare(y, other.y);
        return order != 0 ? order : Integer.compare(z, other.z);
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
