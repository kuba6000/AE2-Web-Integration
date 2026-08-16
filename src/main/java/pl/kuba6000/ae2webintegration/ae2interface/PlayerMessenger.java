package pl.kuba6000.ae2webintegration.ae2interface;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import cpw.mods.fml.common.FMLCommonHandler;
import pl.kuba6000.ae2webintegration.core.api.IPlayerMessenger;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public class PlayerMessenger implements IPlayerMessenger {

    @Override
    public void sendMessage(PlayerIdentity player, String message) {
        for (EntityPlayerMP entityPlayerMP : (List<EntityPlayerMP>) FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getConfigurationManager().playerEntityList) {
            if (entityPlayerMP.getUniqueID()
                .equals(player.uuid)) {
                entityPlayerMP.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.GREEN.toString() + EnumChatFormatting.BOLD.toString() + message));
                return;
            }
        }
    }
}
