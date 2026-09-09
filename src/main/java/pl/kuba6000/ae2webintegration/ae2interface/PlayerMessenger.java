package pl.kuba6000.ae2webintegration.ae2interface;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;

import pl.kuba6000.ae2webintegration.core.api.IPlayerMessenger;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public class PlayerMessenger implements IPlayerMessenger {

    @Override
    public void sendMessage(PlayerIdentity player, String message) {
        for (EntityPlayerMP entityPlayerMP : FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getPlayerList()
            .getPlayers()) {
            if (entityPlayerMP.getUniqueID()
                .equals(player.uuid)) {
                entityPlayerMP.sendMessage(
                    new TextComponentString(TextFormatting.GREEN.toString() + TextFormatting.BOLD + message));
                return;
            }
        }
    }
}
