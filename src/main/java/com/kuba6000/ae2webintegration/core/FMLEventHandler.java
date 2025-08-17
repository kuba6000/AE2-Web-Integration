package com.kuba6000.ae2webintegration.core;

import static com.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import com.kuba6000.ae2webintegration.core.utils.VersionChecker;

@Mod.EventBusSubscriber(modid = MODID)
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
