package pl.kuba6000.ae2webintegration.core.api;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

public class PlayerIdentity {

    public final @NotNull UUID uuid;
    public final @NotNull String name;

    public PlayerIdentity(@NotNull UUID uuid, @NotNull String name) {
        this.uuid = uuid;
        this.name = name;
    }
}
