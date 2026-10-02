package pl.kuba6000.ae2webintegration.ae2interface.platform;

import java.io.File;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import cpw.mods.fml.common.FMLCommonHandler;
import pl.kuba6000.ae2webintegration.ae2interface.config.LegacyConfigReader;
import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;

@Desugar
public record Platform(File configDir) implements IServerPlatform {

    @Override
    public @NotNull IconPack.Target getIconPackTarget() {
        return new IconPack.Target("1.7.10", "forge", "ae2wi-legacy-1.7.10-v1", "ae2wi-legacy-1.7.10-base-v1");
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
