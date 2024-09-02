package com.kuba6000.ae2webintegration;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.kuba6000.ae2webintegration.ae2sync.ISyncedRequest;
import com.kuba6000.ae2webintegration.utils.VersionChecker;

import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
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
                if (!AE2Controller.isValid()) {
                    request.deny("NO_SYSTEM");
                } else {
                    request.handle(AE2Controller.activeGrid);
                }
            }
        }

        if (AE2Controller.timer % 100 != 0) return;
        if (!AE2Controller.isValid()) {
            for (Grid grid : TickHandler.INSTANCE.getGridList()) {
                IPathingGrid pathingGrid = grid.getCache(IPathingGrid.class);
                if (pathingGrid != null && !pathingGrid.isNetworkBooting()
                    && pathingGrid.getControllerState() == ControllerState.CONTROLLER_ONLINE) {
                    ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
                    if (craftingGrid != null) {
                        if ((long) craftingGrid.getCpus()
                            .size() > 5) {
                            AE2Controller.activeGrid = grid;
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        if (VersionChecker.isOutdated()) event.player.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.GREEN.toString() + EnumChatFormatting.BOLD
                    + "----> AE2WebIntegration -> New version detected! Consider updating at https://github.com/kuba6000/AE2-Web-Integration/releases/latest"));
    }

}
