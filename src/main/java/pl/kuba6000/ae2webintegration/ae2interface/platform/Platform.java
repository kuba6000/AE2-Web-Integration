package pl.kuba6000.ae2webintegration.ae2interface.platform;

import java.io.File;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.ae2interface.config.LegacyConfigReader;
import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

public class Platform implements IServerPlatform {

    private static final String MOD_VERSION = ModLoadingContext.get()
        .getActiveContainer()
        .getModInfo()
        .getVersion()
        .toString();

    @Override
    public @NotNull String getModVersion() {
        return MOD_VERSION;
    }

    @Override
    public @NotNull String getLoader() {
        return PlatformConstants.LOADER;
    }

    @Override
    public @NotNull String getMinecraftVersion() {
        return PlatformConstants.MINECRAFT_VERSION;
    }

    @Override
    public @NotNull String getIconPackCompatibilityVersion() {
        return PlatformConstants.ICON_PACK_COMPATIBILITY_VERSION;
    }

    @Override
    public File getWorldDirectory() {
        return ServerLifecycleHooks.getCurrentServer()
            .getWorldPath(LevelResource.ROOT)
            .toFile();
    }

    @Override
    public UUID getOnlinePlayerUUID(String username) {
        if (ServerLifecycleHooks.getCurrentServer() == null) return null;
        ServerPlayer player = ServerLifecycleHooks.getCurrentServer()
            .getPlayerList()
            .getPlayerByName(username);
        return player != null ? player.getUUID() : null;
    }

    @Override
    public File getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get()
            .toFile();
    }

    @Override
    public ILegacyConfigProvider getLegacyConfig() {
        return LegacyConfigReader.open(getConfigDirectory());
    }
}
