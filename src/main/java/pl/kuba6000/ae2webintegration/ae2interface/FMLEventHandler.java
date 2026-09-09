package pl.kuba6000.ae2webintegration.ae2interface;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.WorldEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.UpdateNotifier;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.commands.CommandBootstrap;

public class FMLEventHandler {

    private static final PlayerMessenger messenger = new PlayerMessenger();

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return;
        CoreEngine.onServerTick();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP player)) return;
        PlayerIdentity identity = new PlayerIdentity(player.getUniqueID(), player.getCommandSenderName());
        CoreEngine.onPlayerSeen(identity);
        // "seed" is only the command name for the legacy permission check.
        UpdateNotifier.onPlayerLoggedIn(
            messenger,
            identity,
            player.canCommandSenderUseCommand(CommandBootstrap.ADMIN_PERMISSION_LEVEL, "seed"));
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world instanceof WorldServer) {
            AE.getInstance()
                .clearPlayerSources(event.world);
        }
    }
}
