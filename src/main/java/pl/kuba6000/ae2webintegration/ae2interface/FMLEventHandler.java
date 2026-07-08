package pl.kuba6000.ae2webintegration.ae2interface;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.UpdateNotifier;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

@EventBusSubscriber(modid = AE2WebIntegration.MODID)
public class FMLEventHandler {

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        ++AE2Controller.timer;
        if (AE2Controller.timer % 5 == 0) {
            while (AE2Controller.requests.peek() != null) {
                ISyncedRequest request = AE2Controller.requests.poll();
                request.handle(AE2Controller.AE2Interface);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        PlayerIdentity identity = new PlayerIdentity(serverPlayer.getUUID(), serverPlayer.getScoreboardName());
        UpdateNotifier.onPlayerLoggedIn(new PlayerMessenger(), identity, serverPlayer.hasPermissions(4));
    }
}
