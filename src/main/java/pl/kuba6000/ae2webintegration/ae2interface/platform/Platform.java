package pl.kuba6000.ae2webintegration.ae2interface.platform;

import java.io.File;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

public class Platform implements IServerPlatform {

    private final File configDir;

    public Platform(File configDir) {
        this.configDir = configDir;
    }

    @Override
    public UUID getOnlinePlayerUUID(String username) {
        for (EntityPlayerMP entityPlayerMP : (List<EntityPlayerMP>) FMLCommonHandler.instance()
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
    public UUID getOfflinePlayerUUID(String username) {
        GameProfile profile = FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .func_152358_ax()
            .func_152655_a(username);
        if (profile != null) {
            return profile.getId();
        }
        return null;
    }

    @Override
    public File getConfigDirectory() {
        return configDir;
    }
}
