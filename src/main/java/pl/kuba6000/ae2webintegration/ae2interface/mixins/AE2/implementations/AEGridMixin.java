package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.networking.storage.IStorageGrid;
import appeng.me.Grid;
import appeng.me.GridNode;
import appeng.me.helpers.PlayerSource;
import appeng.parts.reporting.AbstractPartTerminal;
import appeng.util.Platform;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.GridWorldAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPermissions;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPlayerSource;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.GridDiscovery;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.ChatCapturingFakePlayer;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.ChatCapturingPlayerSource;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.PlayerSourceLifecycle;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

@Mixin(value = Grid.class, remap = false)
public abstract class AEGridMixin
    implements IAEGrid, IGridPermissions, IGridPlayerSource, GridWorldAccessor, PlayerSourceLifecycle {

    @Unique
    private final Object web$permissionLock = new Object();

    @Unique
    private final Map<UUID, List<GridAccessSource>> web$permissions = new HashMap<>();

    @Unique
    private final Map<GridNode, @Nullable GridAccessSource> web$ownerSources = new IdentityHashMap<>();

    @Unique
    private final Map<GridNode, List<GridAccessSource>> web$securitySources = new IdentityHashMap<>();

    @Inject(method = "add(Lappeng/me/GridNode;)V", at = @At("RETURN"))
    private void web$nodeAdded(GridNode node, CallbackInfo callback) {
        if (!Platform.isServer()) return;
        Grid grid = (Grid) (Object) this;
        // Cache and gridChanged callbacks can remove or move the node before add returns.
        if (node.getGrid() != grid || !grid.getMachines(
            node.getMachine()
                .getClass())
            .contains(node)) return;
        if (node.getMachine() instanceof ISecurityProvider) {
            web$securitySources.putIfAbsent(node, Collections.emptyList());
            web$securityChanged();
        } else if (GridDiscovery.accessSourceKind(
            node.getMachine()
                .getClass())
            != null) {
                web$ownerSources.putIfAbsent(node, null);
                web$ownerChanged(node);
            }
    }

    @Inject(method = "remove(Lappeng/me/GridNode;)V", at = @At("HEAD"))
    private void web$nodeRemoved(GridNode node, CallbackInfo callback) {
        if (!Platform.isServer()) return;
        synchronized (web$permissionLock) {
            GridAccessSource owner = web$ownerSources.remove(node);
            if (owner != null) {
                web$removeSource(owner);
            }
            List<GridAccessSource> security = web$securitySources.remove(node);
            if (security != null) {
                for (GridAccessSource source : security) web$removeSource(source);
            }
        }
    }

    @Inject(method = "remove(Lappeng/me/GridNode;)V", at = @At("RETURN"))
    private void web$securityNodeRemoved(GridNode node, CallbackInfo callback) {
        if (Platform.isServer() && node.getMachine() instanceof ISecurityProvider) web$securityChanged();
    }

    @Override
    public void web$ownerChanged(@NotNull GridNode node) {
        if (web$securitySources.containsKey(node)) {
            web$securityChanged();
            return;
        }
        // Destroyed nodes retain myGrid, so only current members can update grants.
        if (!web$ownerSources.containsKey(node)) return;
        GridAccessSource source = GridDiscovery.ownerSource(node);
        synchronized (web$permissionLock) {
            GridAccessSource previous = web$ownerSources.put(node, source);
            if (previous != null) {
                web$removeSource(previous);
            }
            if (source != null) {
                web$addSource(source);
            }
        }
    }

    @Override
    public void web$securityChanged() {
        ISecurityGrid security = ((Grid) (Object) this).getCache(ISecurityGrid.class);
        GridNode provider = security.isAvailable() && web$securitySources.size() == 1 ? web$securitySources.keySet()
            .iterator()
            .next() : null;
        List<GridAccessSource> replacement = provider == null ? Collections.emptyList()
            : GridDiscovery.securitySources(provider);
        synchronized (web$permissionLock) {
            for (Map.Entry<GridNode, List<GridAccessSource>> entry : web$securitySources.entrySet()) {
                for (GridAccessSource previous : entry.getValue()) web$removeSource(previous);
                List<GridAccessSource> sources = entry.getKey() == provider ? replacement : Collections.emptyList();
                entry.setValue(sources);
                for (GridAccessSource source : sources) web$addSource(source);
            }
        }
    }

    @Unique
    private void web$addSource(@NotNull GridAccessSource source) {
        web$permissions.computeIfAbsent(source.player().uuid, player -> new ArrayList<>())
            .add(source);
    }

    @Unique
    private void web$removeSource(@NotNull GridAccessSource source) {
        UUID player = source.player().uuid;
        List<GridAccessSource> sources = web$permissions.get(player);
        sources.remove(source);
        if (sources.isEmpty()) web$permissions.remove(player);
    }

    @Override
    public boolean web$hasAccess(@NotNull UUID player) {
        synchronized (web$permissionLock) {
            return web$permissions.containsKey(player);
        }
    }

    @Override
    public @NotNull Map<UUID, List<GridAccessSource>> web$getPermissions() {
        return web$permissions;
    }

    @Override
    public @NotNull Set<DimensionalCoords> web$getControllers() {
        return GridDiscovery.controllers((Grid) (Object) this);
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
