package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.mojang.authlib.GameProfile;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.me.Grid;
import appeng.parts.reporting.AbstractPartTerminal;
import cpw.mods.fml.common.FMLCommonHandler;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.ChatCapturingFakePlayer;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.ChatCapturingPlayerSource;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.PlayerSourceLifecycle;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

@Mixin(value = Grid.class, remap = false)
public abstract class AEGridMixin implements IAEGrid, PlayerSourceLifecycle {

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
    public IAESecurityGrid web$getSecurityGrid() {
        return ((Grid) (Object) this).getCache(ISecurityGrid.class);
    }

    @Override
    public boolean web$isEmpty() {
        return ((Grid) (Object) this).isEmpty();
    }

    @Unique
    private Class<? extends IGridHost> web$lastUsedMachineClass = null;

    @Unique
    private ChatCapturingPlayerSource web$cachedPlayerSource = null;

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
        IActionHost actionHost;
        World world;
        if (web$lastUsedMachineClass == null || terminals.isEmpty()) {
            // throw new RuntimeException("There is no terminal in the AE system");
            actionHost = null;
            world = FMLCommonHandler.instance()
                .getMinecraftServerInstance()
                .worldServerForDimension(0);
        } else {
            IGridNode node = terminals.iterator()
                .next();
            actionHost = (IActionHost) node.getMachine();
            world = node.getWorld();
        }

        if (web$cachedPlayerSource != null) {
            if (web$cachedPlayerSource.via != actionHost || !web$cachedPlayerSource.isForWorld(world)) {
                web$cachedPlayerSource.dispose();
                web$cachedPlayerSource = null;
            } else return web$cachedPlayerSource;
        }

        web$cachedPlayerSource = new ChatCapturingPlayerSource(
            new ChatCapturingFakePlayer(
                (WorldServer) world,
                new GameProfile(AE2Controller.AEControllerUUID, "AE2CONTROLLER")),
            actionHost);

        return web$cachedPlayerSource;
    }

    @Override
    public void web$clearPlayerSource(World world) {
        if (web$cachedPlayerSource == null || world != null && !web$cachedPlayerSource.isForWorld(world)) return;
        web$cachedPlayerSource.dispose();
        web$cachedPlayerSource = null;
    }

}
