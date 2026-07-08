package pl.kuba6000.ae2webintegration.core.api;

import java.io.File;
import java.util.UUID;

public interface IServerPlatform {

    UUID getOnlinePlayerUUID(String username);

    UUID getRegisteredPlayerUUID(String username);

    File getConfigDirectory();
}
