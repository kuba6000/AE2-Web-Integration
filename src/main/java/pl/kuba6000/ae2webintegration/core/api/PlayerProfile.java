package pl.kuba6000.ae2webintegration.core.api;

import java.util.UUID;

public class PlayerProfile {
    private final UUID uuid;
    private final String name;

    public PlayerProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUUID() {
        return uuid;
    }

    public String getName() {
        return name;
    }
}
