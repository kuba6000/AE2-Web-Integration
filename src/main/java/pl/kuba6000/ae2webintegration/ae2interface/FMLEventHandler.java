package pl.kuba6000.ae2webintegration.ae2interface;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.UpdateNotifier;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public class FMLEventHandler {

    private static final PlayerMessenger messenger = new PlayerMessenger();

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return;
        CoreEngine.onServerTick();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        PlayerIdentity identity = new PlayerIdentity(player.getUniqueID(), player.getName());
        CoreEngine.onPlayerSeen(identity);
        UpdateNotifier.onPlayerLoggedIn(messenger, identity, player.canUseCommand(4, "seed"));
    }
}
