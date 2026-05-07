package pl.kuba6000.ae2webintegration.core.api;

import java.io.File;
import java.util.UUID;

public interface IServerPlatform {

    /**
     * Resolves a UUID of an online player by their username.
     * 
     * @return The UUID, or null if the player is not online.
     */
    UUID getOnlinePlayerUUID(String username);

    /**
     * Resolves a UUID from the server's cache/offline data for a given username.
     * 
     * @return The UUID, or null if not found.
     */
    UUID getOfflinePlayerUUID(String username);

    /**
     * Gets the main config directory.
     */
    File getConfigDirectory();
}
