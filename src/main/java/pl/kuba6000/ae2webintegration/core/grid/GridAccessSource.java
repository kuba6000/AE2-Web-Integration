package pl.kuba6000.ae2webintegration.core.grid;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

/**
 * A block or part granting a specific player access to the grid.
 *
 * @param player   player receiving access
 * @param kind     identifier of the type of block or part granting access
 * @param position world position of that block or part's host
 * @param side     side of the host occupied by the part, or {@code null} when no side applies
 * @param reason   identifier of the reason access is granted, such as ownership or a security card
 * @example kind controller
 * @example side null
 * @example reason node_owner
 */
@Desugar
public record GridAccessSource(@NotNull PlayerIdentity player, @NotNull String kind,
    @NotNull DimensionalCoords position, @Nullable String side, @NotNull String reason) {}
