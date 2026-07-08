package pl.kuba6000.ae2webintegration.core.api;

import java.io.File;
import java.util.UUID;

public interface IServerPlatform {

    UUID getOnlinePlayerUUID(String username);

    UUID getOfflinePlayerUUID(String username);

    default UUID getRegisteredPlayerUUID(String username) {
        return getOfflinePlayerUUID(username);
    }

    File getConfigDirectory();
}
