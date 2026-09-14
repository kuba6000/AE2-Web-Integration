package pl.kuba6000.ae2webintegration.core.api;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Detached explanation of a current grant. A null player explicitly denotes a default grant. */
public final class GridAccessSource {

    public final @Nullable PlayerIdentity player;
    @SuppressWarnings("unused") // Serialized by Gson for the web access explanation.
    public final @NotNull String kind;
    @SuppressWarnings("unused") // Serialized by Gson for the web access explanation.
    public final @NotNull DimensionalCoords position;
    @SuppressWarnings("unused") // Serialized by Gson for the web access explanation.
    public final @Nullable String side;
    @SuppressWarnings("unused") // Serialized by Gson for the web access explanation.
    public final @NotNull String reason;
    public final @NotNull Set<UUID> excludedPlayers;

    // Jabel changes syntax support; Set.copyOf is unavailable on the Java 8 runtime.
    @SuppressWarnings("Java9CollectionFactory")
    private GridAccessSource(@Nullable PlayerIdentity player, @NotNull String kind, @NotNull DimensionalCoords position,
        @Nullable String side, @NotNull String reason, @NotNull Collection<UUID> excludedPlayers) {
        this.player = player;
        this.kind = kind;
        this.position = position;
        this.side = side;
        this.reason = reason;
        this.excludedPlayers = Collections.unmodifiableSet(new HashSet<>(excludedPlayers));
    }

    public static @NotNull GridAccessSource forPlayer(@NotNull PlayerIdentity player, @NotNull String kind,
        @NotNull DimensionalCoords position, @Nullable String side, @NotNull String reason) {
        return new GridAccessSource(player, kind, position, side, reason, Collections.emptySet());
    }

    public static @NotNull GridAccessSource forEveryone(@NotNull String kind, @NotNull DimensionalCoords position,
        @NotNull String reason, @NotNull Collection<UUID> excludedPlayers) {
        return new GridAccessSource(null, kind, position, null, reason, excludedPlayers);
    }

    public boolean allows(@NotNull UUID playerId) {
        return player == null ? !excludedPlayers.contains(playerId) : player.uuid.equals(playerId);
    }
}
