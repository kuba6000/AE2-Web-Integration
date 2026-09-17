package pl.kuba6000.ae2webintegration.core.api;

import java.io.File;
import java.util.Map;
import java.util.UUID;

public interface IServerPlatform {

    /** Must only be called by a task running on the Minecraft server thread. */
    UUID getOnlinePlayerUUID(String username);

    File getConfigDirectory();

    /** Reads the old loader configuration without changing it; absent files return an empty map. */
    Map<String, Object> readLegacyConfig();

    /** Root of the active server save, available after server startup. */
    File getWorldDirectory();
}
