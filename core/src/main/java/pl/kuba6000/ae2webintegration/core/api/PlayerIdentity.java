package pl.kuba6000.ae2webintegration.core.api;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/** A Minecraft player identified independently of their username. */
public class PlayerIdentity {

    /**
     * Persistent Minecraft player UUID.
     *
     * @example 095be615-a8ad-4c33-8e9c-c7612fbf6c9f
     */
    public final @NotNull UUID uuid;
    /**
     * Minecraft username last known to the server.
     *
     * @example ExamplePlayer
     */
    public final @NotNull String name;

    public PlayerIdentity(@NotNull UUID uuid, @NotNull String name) {
        this.uuid = uuid;
        this.name = name;
    }
}
