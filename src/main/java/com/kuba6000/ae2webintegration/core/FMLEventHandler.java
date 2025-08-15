package com.kuba6000.ae2webintegration.core;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import com.kuba6000.ae2webintegration.core.utils.VersionChecker;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

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
        if (VersionChecker.isOutdated() && event.player.canCommandSenderUseCommand(4, "seed"))
            event.player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.GREEN.toString() + EnumChatFormatting.BOLD
                        + "----> AE2WebIntegration -> New version detected! Consider updating at https://github.com/kuba6000/AE2-Web-Integration/releases/latest"));
    }

}
