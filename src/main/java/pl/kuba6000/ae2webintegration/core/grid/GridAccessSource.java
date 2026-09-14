package pl.kuba6000.ae2webintegration.core.grid;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

/** Detached explanation of a current grant. A null player explicitly denotes a default grant. */
@Desugar
public record GridAccessSource(@Nullable PlayerIdentity player, @NotNull String kind,
    @NotNull DimensionalCoords position, @Nullable String side, @NotNull String reason,
    @NotNull Set<UUID> excludedPlayers) {

    // Jabel changes syntax support; Set.copyOf is unavailable on the Java 8 runtime.
    @SuppressWarnings("Java9CollectionFactory")
    public GridAccessSource {
        excludedPlayers = Collections.unmodifiableSet(new HashSet<>(excludedPlayers));
    }

    public static @NotNull GridAccessSource forPlayer(@NotNull PlayerIdentity player, @NotNull String kind,
        @NotNull DimensionalCoords position, @Nullable String side, @NotNull String reason) {
        return new GridAccessSource(player, kind, position, side, reason, Collections.emptySet());
    }

    public static @NotNull GridAccessSource forEveryone(@NotNull String kind, @NotNull DimensionalCoords position,
        @NotNull String reason, @NotNull Set<UUID> excludedPlayers) {
        return new GridAccessSource(null, kind, position, null, reason, excludedPlayers);
    }

    public boolean allows(@NotNull UUID playerId) {
        return player == null ? !excludedPlayers.contains(playerId) : player.uuid.equals(playerId);
    }
}
