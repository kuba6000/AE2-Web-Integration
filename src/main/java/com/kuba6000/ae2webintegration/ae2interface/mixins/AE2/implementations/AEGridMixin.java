package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.me.Grid;
import appeng.parts.reporting.AbstractPartTerminal;

@Mixin(value = Grid.class, remap = false)
public abstract class AEGridMixin implements IAEGrid {

    @Override
    public IAECraftingGrid web$getCraftingGrid() {
        return ((Grid) (Object) this).getCache(ICraftingGrid.class);
    }

    @Override
    public IAEPathingGrid web$getPathingGrid() {
        return ((Grid) (Object) this).getCache(IPathingGrid.class);
    }

    @Override
    public IAEStorageGrid web$getStorageGrid() {
        return ((Grid) (Object) this).getCache(IStorageGrid.class);
    }

    @Override
    public boolean web$isEmpty() {
        return ((Grid) (Object) this).isEmpty();
    }

    @Unique
    private Class<? extends IGridHost> web$lastUsedMachineClass = null;

    @Unique
    public IChatComponent web$lastFakePlayerChatMessage;

    @Unique
    private PlayerSource web$cachedPlayerSource = null;

    @Override
    public Object web$getPlayerSource() {
        Grid internalGrid = (Grid) (Object) this;
        IMachineSet terminals = null;
        if (web$lastUsedMachineClass != null) terminals = internalGrid.getMachines(web$lastUsedMachineClass);
        if (web$lastUsedMachineClass == null || terminals.isEmpty()) {
            web$lastUsedMachineClass = null;
            Iterable<Class<? extends IGridHost>> machines = internalGrid.getMachineClasses();
            for (Class<? extends IGridHost> machine : machines) {
                if (AbstractPartTerminal.class.isAssignableFrom(machine)
                    && !(terminals = internalGrid.getMachines(machine)).isEmpty()) {
                    web$lastUsedMachineClass = machine;
                    break;
                }
            }
        }
        if (web$lastUsedMachineClass == null || terminals.isEmpty()) {
            throw new RuntimeException("There is no terminal in the AE system");
        }
        IGridNode node = terminals.iterator()
            .next();
        IActionHost actionHost = (IActionHost) node.getMachine();
        World world = node.getWorld();

        if (web$cachedPlayerSource != null) {
            if (web$cachedPlayerSource.via != actionHost) web$cachedPlayerSource = null;
            else return web$cachedPlayerSource;
        }

        web$cachedPlayerSource = new PlayerSource(
            new FakePlayer((WorldServer) world, AE2Controller.AEControllerProfile) {

                @Override
                public void addChatMessage(IChatComponent message) {
                    web$lastFakePlayerChatMessage = message;
                }
            },
            actionHost);

        return web$cachedPlayerSource;
    }

    @Override
    public IChatComponent web$getLastFakePlayerChatMessage() {
        return web$lastFakePlayerChatMessage;
    }
}
