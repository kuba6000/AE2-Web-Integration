package pl.kuba6000.ae2webintegration.core.grid;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

/** Detached explanation of a current grant to a specific player. */
@Desugar
public record GridAccessSource(@NotNull PlayerIdentity player, @NotNull String kind,
    @NotNull DimensionalCoords position, @Nullable String side, @NotNull String reason) {}
