package pl.kuba6000.ae2webintegration.core.api;

import java.util.UUID;

public class PlayerIdentity {

    public final UUID uuid;
    public final String name;

    public PlayerIdentity(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }
}
