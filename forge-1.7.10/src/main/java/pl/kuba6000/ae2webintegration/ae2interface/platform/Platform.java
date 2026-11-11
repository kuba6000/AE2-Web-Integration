package pl.kuba6000.ae2webintegration.ae2interface.platform;

import static pl.kuba6000.ae2webintegration.ae2interface.platform.PlatformConstants.ICON_PACK_COMPATIBILITY_VERSION;
import static pl.kuba6000.ae2webintegration.ae2interface.platform.PlatformConstants.MINECRAFT_VERSION;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.launchwrapper.Launch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import pl.kuba6000.ae2webintegration.Tags;
import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
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
        return MINECRAFT_VERSION;
    }

    @Override
    public @NotNull String getIconPackCompatibilityVersion() {
        return ICON_PACK_COMPATIBILITY_VERSION;
    }

    @Override
    public @Nullable Path getModFile() {
        if (Boolean.TRUE.equals(Launch.blackboard.get("fml.deobfuscatedEnvironment"))) return null;
        return Loader.instance()
            .getIndexedModList()
            .get(AE2WebIntegration.MODID)
            .getSource()
            .toPath();
    }

    @Override
    public boolean isSingleplayer() {
        return FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .isSinglePlayer();
    }

    @Override
    public UUID getOnlinePlayerUUID(String username) {
        for (EntityPlayerMP entityPlayerMP : FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getConfigurationManager().playerEntityList) {
            if (entityPlayerMP.getCommandSenderName()
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
            .worldServerForDimension(0)
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
