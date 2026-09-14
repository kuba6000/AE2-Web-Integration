package pl.kuba6000.ae2webintegration.core.api;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

@Desugar
public record DimensionalCoords(@NotNull String dimid, int x, int y, int z) implements Comparable<DimensionalCoords> {

    public DimensionalCoords(int dimid, int x, int y, int z) {
        this(String.valueOf(dimid), x, y, z);
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

}
