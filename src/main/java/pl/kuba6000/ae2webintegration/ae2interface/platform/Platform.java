package pl.kuba6000.ae2webintegration.ae2interface.platform;

import java.io.File;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

public class Platform implements IServerPlatform {

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
}
