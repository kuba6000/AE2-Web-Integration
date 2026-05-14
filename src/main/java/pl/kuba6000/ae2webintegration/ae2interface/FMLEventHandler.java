package pl.kuba6000.ae2webintegration.ae2interface;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.UpdateNotifier;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public class FMLEventHandler {

    private static final PlayerMessenger messenger = new PlayerMessenger();

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return;
        ++AE2Controller.timer;
        if (AE2Controller.timer % 5 == 0) {
            while (AE2Controller.requests.peek() != null) {
                ISyncedRequest request = AE2Controller.requests.poll();
                request.handle(AE2Controller.AE2Interface);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (!player.canCommandSenderUseCommand(4, "seed")) return;
        UpdateNotifier
            .notifyPlayerIfOutdated(messenger, new PlayerIdentity(player.getUniqueID(), player.getCommandSenderName()));
    }
}
