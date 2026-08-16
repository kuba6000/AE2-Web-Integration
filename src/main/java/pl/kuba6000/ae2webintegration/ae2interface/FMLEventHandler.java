package pl.kuba6000.ae2webintegration.ae2interface;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.UpdateNotifier;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

@Mod.EventBusSubscriber(modid = AE2WebIntegration.MODID)
public class FMLEventHandler {

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return;
        CoreEngine.onServerTick();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        PlayerIdentity identity = new PlayerIdentity(
            serverPlayer.getUUID(),
            serverPlayer.getGameProfile()
                .getName());
        CoreEngine.onPlayerSeen(identity);
        UpdateNotifier.onPlayerLoggedIn(new PlayerMessenger(), identity, serverPlayer.hasPermissions(4));
    }
}
