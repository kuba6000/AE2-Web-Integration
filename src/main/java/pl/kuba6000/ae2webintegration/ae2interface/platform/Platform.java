package pl.kuba6000.ae2webintegration.ae2interface.platform;

import java.io.File;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.github.bsideup.jabel.Desugar;

import cpw.mods.fml.common.FMLCommonHandler;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

@Desugar
public record Platform(File configDir) implements IServerPlatform {

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
}
