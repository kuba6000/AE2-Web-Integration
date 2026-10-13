package pl.kuba6000.ae2webintegration.core.api;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/**
 * Block position within a Minecraft dimension.
 *
 * @param dimensionId dimension identifier; a numeric string on legacy versions or a resource identifier on modern
 *                    versions
 * @param x           block X coordinate
 * @param y           block Y coordinate
 * @param z           block Z coordinate
 * @example dimensionId minecraft:overworld
 * @example x 120
 * @example y 64
 * @example z -32
 */
@Desugar
public record DimensionalCoords(@NotNull String dimensionId, int x, int y, int z)
    implements Comparable<DimensionalCoords> {

    public DimensionalCoords(int dimensionId, int x, int y, int z) {
        this(String.valueOf(dimensionId), x, y, z);
    }

    /** Lexicographic dimension/XYZ order, independent of the native dimension naming scheme. */
    @Override
    public int compareTo(@NotNull DimensionalCoords other) {
        int order = dimensionId.compareTo(other.dimensionId);
        if (order != 0) return order;
        order = Integer.compare(x, other.x);
        if (order != 0) return order;
        order = Integer.compare(y, other.y);
        return order != 0 ? order : Integer.compare(z, other.z);
    }

}
