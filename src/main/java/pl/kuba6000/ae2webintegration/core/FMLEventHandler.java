package pl.kuba6000.ae2webintegration.core;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

@EventBusSubscriber(modid = MODID)
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
        if (!(player instanceof ServerPlayer)) return;
        if (VersionChecker.isOutdated() && player.hasPermissions(4)) {
            player.sendSystemMessage(
                Component.literal(
                    ChatFormatting.GREEN.toString() + ChatFormatting.BOLD
                        + "----> AE2WebIntegration -> New version detected! Consider updating at https://github.com/kuba6000/AE2-Web-Integration/releases/latest"));
        }
    }

}
