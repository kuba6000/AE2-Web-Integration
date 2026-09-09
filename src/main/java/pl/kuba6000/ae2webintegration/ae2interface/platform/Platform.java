package pl.kuba6000.ae2webintegration.ae2interface.platform;

import java.io.File;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.FMLCommonHandler;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

public class Platform implements IServerPlatform {

    private final File configDir;

    public Platform(File configDir) {
        this.configDir = configDir;
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
    public File getConfigDirectory() {
        return configDir;
    }
}
