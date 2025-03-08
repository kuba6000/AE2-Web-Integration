package com.kuba6000.ae2webintegration.ae2interface.implementations;

import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import com.kuba6000.ae2webintegration.ae2interface.implementations.service.AECraftingGrid;
import com.kuba6000.ae2webintegration.ae2interface.implementations.service.AEPathingGrid;
import com.kuba6000.ae2webintegration.ae2interface.implementations.service.AEStorageGrid;
import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

import appeng.api.networking.IGrid;
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

public class AEGrid extends IAEObject<IGrid> implements IAEGrid {

    public AEGrid(IGrid grid) {
        super(grid);
    }

    @Override
    public IAECraftingGrid getCraftingGrid() {
        return new AECraftingGrid(get().getCache(ICraftingGrid.class));
    }

    @Override
    public IAEPathingGrid getPathingGrid() {
        return new AEPathingGrid(get().getCache(IPathingGrid.class));
    }

    @Override
    public IAEStorageGrid getStorageGrid() {
        return new AEStorageGrid(get().getCache(IStorageGrid.class));
    }

    @Override
    public boolean isEmpty() {
        return get().isEmpty();
    }

    @Override
    public boolean internalObjectEquals(IAEGrid obj) {
        return get() == ((AEGrid) obj).get();
    }

    private Class<? extends IGridHost> lastUsedMachineClass = null;
    public IChatComponent lastFakePlayerChatMessage;

    private PlayerSource cachedPlayerSource = null;

    public PlayerSource getPlayerSource() {
        Grid internalGrid = (Grid) get();
        IMachineSet terminals = null;
        if (lastUsedMachineClass != null) terminals = internalGrid.getMachines(lastUsedMachineClass);
        if (lastUsedMachineClass == null || terminals.isEmpty()) {
            lastUsedMachineClass = null;
            Iterable<Class<? extends IGridHost>> machines = internalGrid.getMachineClasses();
            for (Class<? extends IGridHost> machine : machines) {
                if (AbstractPartTerminal.class.isAssignableFrom(machine)
                    && !(terminals = internalGrid.getMachines(machine)).isEmpty()) {
                    lastUsedMachineClass = machine;
                    break;
                }
            }
        }
        if (lastUsedMachineClass == null || terminals.isEmpty()) {
            throw new RuntimeException("There is no terminal in the AE system");
        }
        IGridNode node = terminals.iterator()
            .next();
        IActionHost actionHost = (IActionHost) node.getMachine();
        World world = node.getWorld();

        if (cachedPlayerSource != null) {
            if (cachedPlayerSource.via != actionHost) cachedPlayerSource = null;
            else return cachedPlayerSource;
        }

        cachedPlayerSource = new PlayerSource(new FakePlayer((WorldServer) world, AE2Controller.AEControllerProfile) {

            @Override
            public void addChatMessage(IChatComponent message) {
                lastFakePlayerChatMessage = message;
            }
        }, actionHost);

        return cachedPlayerSource;
    }
}
