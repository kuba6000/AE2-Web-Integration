package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.List;
import java.util.Set;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.authlib.GameProfile;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.storage.IStorageGrid;
import appeng.me.Grid;
import appeng.me.GridNode;
import appeng.me.helpers.PlayerSource;
import appeng.parts.reporting.AbstractPartTerminal;
import appeng.util.Platform;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.GridWorldAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPlayerSource;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.GridDiscovery;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.ChatCapturingFakePlayer;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.ChatCapturingPlayerSource;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.PlayerSourceLifecycle;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

@Mixin(value = Grid.class, remap = false)
public abstract class AEGridMixin implements IAEGrid, IGridPlayerSource, GridWorldAccessor, PlayerSourceLifecycle {

    @Inject(method = { "add(Lappeng/me/GridNode;)V", "remove(Lappeng/me/GridNode;)V" }, at = @At("HEAD"))
    private void web$membershipChanged(GridNode node, CallbackInfo callback) {
        if (!Platform.isServer()) return;
        String kind = GridDiscovery.accessSourceKind(
            node.getMachine()
                .getClass());
        if (kind == null) return;
        GridAccessSessions.permissionsChanged();
    }

    @Override
    public @NotNull Set<DimensionalCoords> web$getControllers() {
        return GridDiscovery.controllers((Grid) (Object) this);
    }

    @Override
    public @NotNull List<GridAccessSource> web$getAccessSources() {
        return GridDiscovery.accessSources((Grid) (Object) this);
    }

    @Override
    public @Nullable PlayerIdentity web$getRepresentativeOwner() {
        return GridDiscovery.representativeOwner((Grid) (Object) this);
    }

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

    @Unique
    private Class<? extends IGridHost> web$lastUsedMachineClass = null;

    @Unique
    private ChatCapturingPlayerSource web$cachedPlayerSource = null;

    @Unique
    private World web$cachedPlayerSourceWorld = null;

    @Override
    public PlayerSource web$getPlayerSource() {
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
            actionHost = null;
            world = FMLCommonHandler.instance()
                .getMinecraftServerInstance()
                .getWorld(0);
        } else {
            IGridNode node = terminals.iterator()
                .next();
            actionHost = (IActionHost) node.getMachine();
            world = node.getWorld();
        }

        if (web$cachedPlayerSource != null) {
            if (web$cachedPlayerSource.machine()
                .orElse(null) != actionHost || !web$cachedPlayerSource.isForWorld(world)) {
                web$cachedPlayerSource.dispose();
                web$cachedPlayerSource = null;
                web$cachedPlayerSourceWorld = null;
            } else return web$cachedPlayerSource;
        }

        web$cachedPlayerSourceWorld = world;
        web$cachedPlayerSource = new ChatCapturingPlayerSource(
            new ChatCapturingFakePlayer(
                (WorldServer) world,
                new GameProfile(AE2Controller.AEControllerUUID, "AE2CONTROLLER")),
            actionHost);

        return web$cachedPlayerSource;
    }

    @Override
    public World web$getPlayerSourceWorld() {
        if (web$cachedPlayerSourceWorld == null) web$getPlayerSource();
        return web$cachedPlayerSourceWorld;
    }

    @Override
    public void web$clearPlayerSource(World world) {
        if (web$cachedPlayerSource == null || world != null && web$cachedPlayerSourceWorld != world) return;
        web$cachedPlayerSource.dispose();
        web$cachedPlayerSource = null;
        web$cachedPlayerSourceWorld = null;
    }

}
