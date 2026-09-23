package pl.kuba6000.ae2webintegration.core.api;

import java.io.File;
import java.util.UUID;

public interface IServerPlatform {

    /** Must only be called by a task running on the Minecraft server thread. */
    UUID getOnlinePlayerUUID(String username);

    File getConfigDirectory();

    /** Opens legacy settings for migration; called only when the core configuration is absent. */
    @Deprecated // remove after a few versions
    ILegacyConfigProvider getLegacyConfig();

    /** Root of the active server save, available after server startup. */
    File getWorldDirectory();
}
