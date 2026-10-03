package pl.kuba6000.ae2webintegration.ae2interface.platform;

import java.io.File;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.Tags;
import pl.kuba6000.ae2webintegration.ae2interface.config.LegacyConfigReader;
import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

@Desugar
public record Platform(File configDir) implements IServerPlatform {

    @Override
    public @NotNull String getModVersion() {
        return Tags.VERSION;
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
    public UUID getOnlinePlayerUUID(String username) {
        // 1.12.2: getPlayerList returns NetworkPlayerInfo; iterate player entities
        for (EntityPlayerMP entityPlayerMP : FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getPlayerList()
            .getPlayers()) {
            if (entityPlayerMP.getName()
                .equalsIgnoreCase(username)) {
                return entityPlayerMP.getUniqueID();
            }
        }
        return null;
    }

    @Override
    public File getWorldDirectory() {
        return FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getWorld(0)
            .getSaveHandler()
            .getWorldDirectory();
    }

    @Override
    public File getConfigDirectory() {
        return configDir;
    }

    @Override
    public ILegacyConfigProvider getLegacyConfig() {
        return LegacyConfigReader.open(getConfigDirectory());
    }
}
