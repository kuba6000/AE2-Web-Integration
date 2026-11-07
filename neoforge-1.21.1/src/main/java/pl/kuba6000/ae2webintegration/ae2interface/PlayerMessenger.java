package pl.kuba6000.ae2webintegration.ae2interface;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import pl.kuba6000.ae2webintegration.core.api.IPlayerMessenger;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public class PlayerMessenger implements IPlayerMessenger {

    @Override
    public void sendMessage(PlayerIdentity player, String message) {
        if (ServerLifecycleHooks.getCurrentServer() == null) return;
        ServerPlayer serverPlayer = ServerLifecycleHooks.getCurrentServer()
            .getPlayerList()
            .getPlayer(player.uuid);
        if (serverPlayer != null) {
            serverPlayer.sendSystemMessage(
                Component.literal(message)
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        }
    }
}
