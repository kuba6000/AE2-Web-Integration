package pl.kuba6000.ae2webintegration.core;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

public class FMLEventHandler {

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
        if (VersionChecker.isOutdated() && event.player.canUseCommand(4, "seed")) event.player.sendMessage(
            new TextComponentString(
                TextFormatting.GREEN.toString() + TextFormatting.BOLD
                    + "----> AE2WebIntegration -> New version detected! Consider updating at https://github.com/kuba6000/AE2-Web-Integration/releases/latest"));
    }

}
