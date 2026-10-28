package pl.kuba6000.ae2webintegration.core.api;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface IServerPlatform {

    /** Must only be called by a task running on the Minecraft server thread. */
    UUID getOnlinePlayerUUID(String username);

    File getConfigDirectory();

    /** Opens legacy settings for migration; called only when the core configuration is absent. */
    @Deprecated // remove after a few versions
    ILegacyConfigProvider getLegacyConfig();

    /** Root of the active server save, available after server startup. */
    File getWorldDirectory();

    /** Whether the active server is integrated; read on its server thread after startup. */
    boolean isSingleplayer();

    /** Loaded native mod JAR location, or null in the loader development environment. No file I/O. */
    @Nullable
    Path getModFile();

    @NotNull
    String getModVersion();

    @NotNull
    String getLoader();

    @NotNull
    String getMinecraftVersion();

    /** Opaque version compared by exact equality when loading icon packs. */
    @NotNull
    String getIconPackCompatibilityVersion();
}
