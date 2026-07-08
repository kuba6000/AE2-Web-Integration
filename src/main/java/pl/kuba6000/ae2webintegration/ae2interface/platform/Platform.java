package pl.kuba6000.ae2webintegration.ae2interface.platform;

import java.io.File;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;

import com.mojang.authlib.GameProfile;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

public class Platform implements IServerPlatform {

    private final File configDir;

    public Platform(File configDir) {
        this.configDir = configDir;
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
    public UUID getRegisteredPlayerUUID(String username) {
        GameProfile profile = FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getPlayerProfileCache()
            .getGameProfileForUsername(username);
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
