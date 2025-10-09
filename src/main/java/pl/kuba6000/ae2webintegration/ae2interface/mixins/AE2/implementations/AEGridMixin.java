package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.mojang.authlib.GameProfile;

import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.blockentity.networking.WirelessAccessPointBlockEntity;
import appeng.me.Grid;
import appeng.me.helpers.PlayerSource;
import appeng.parts.reporting.AbstractTerminalPart;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

@Mixin(value = Grid.class, remap = false)
public abstract class AEGridMixin implements IAEGrid, IAESecurityGrid {

    @Override
    public IAECraftingGrid web$getCraftingGrid() {
        return (IAECraftingGrid) ((Grid) (Object) this).getCraftingService();
    }

    @Override
    public IAEPathingGrid web$getPathingGrid() {
        return (IAEPathingGrid) ((Grid) (Object) this).getPathingService();
    }

    @Override
    public IAEStorageGrid web$getStorageGrid() {
        return (IAEStorageGrid) ((Grid) (Object) this).getStorageService();
    }

    @Override
    public IAESecurityGrid web$getSecurityGrid() {
        return (IAESecurityGrid) this;
    }

    @Override
    public boolean web$isEmpty() {
        return ((Grid) (Object) this).isEmpty();
    }

    @Unique
    private Class<?> web$lastUsedMachineClass = null;

    @Unique
    public Component web$lastFakePlayerChatMessage;

    @Unique
    private PlayerSource web$cachedPlayerSource = null;

    @Override
    public Object web$getPlayerSource() {
        Grid internalGrid = (Grid) (Object) this;
        Set<?> terminals = null;
        if (web$lastUsedMachineClass != null) terminals = internalGrid.getMachines(web$lastUsedMachineClass);
        if (web$lastUsedMachineClass == null || terminals.isEmpty()) {
            web$lastUsedMachineClass = null;
            Iterable<Class<?>> machines = internalGrid.getMachineClasses();
            for (Class<?> machine : machines) {
                if (AbstractTerminalPart.class.isAssignableFrom(machine)
                    && !(terminals = internalGrid.getMachines(machine)).isEmpty()) {
                    web$lastUsedMachineClass = machine;
                    break;
                }
            }
        }
        IActionHost actionHost;
        ServerLevel world;
        if (web$lastUsedMachineClass == null || terminals.isEmpty()) {
            // throw new RuntimeException("There is no terminal in the AE system");
            Object o = internalGrid.getPivot()
                .getOwner();
            if (o instanceof IActionHost) actionHost = (IActionHost) o;
            else actionHost = null;
            world = ServerLifecycleHooks.getCurrentServer()
                .getLevel(Level.OVERWORLD);
        } else {
            actionHost = (IActionHost) terminals.iterator()
                .next();
            world = actionHost.getActionableNode()
                .getLevel();
        }

        if (web$cachedPlayerSource != null) {
            if (web$cachedPlayerSource.machine()
                .get() != actionHost) web$cachedPlayerSource = null;
            else return web$cachedPlayerSource;
        }

        web$cachedPlayerSource = new PlayerSource(new FakePlayer(world, AE2Controller.AEControllerProfile) {

            @Override
            public void sendSystemMessage(Component p_component, boolean bypassHiddenChat) {
                web$lastFakePlayerChatMessage = p_component;
            }
        }, actionHost);

        return web$cachedPlayerSource;
    }

    @Override
    public Component web$getLastFakePlayerChatMessage() {
        return web$lastFakePlayerChatMessage;
    }

    @Unique
    private final SetMultimap<Integer, IGridNode> web$ownerTracker = MultimapBuilder.hashKeys()
        .hashSetValues()
        .build();

    @Unique
    private int web$hashKey = -1;

    @Unique
    private void web$updateHashKey() {
        if (web$ownerTracker.isEmpty()) {
            web$hashKey = -1;
        } else {
            web$hashKey = web$ownerTracker.values()
                .stream()
                .map(g -> {
                    GlobalPos pos = ((WirelessAccessPointBlockEntity) g.getOwner()).getGlobalPos();
                    return Objects.hash(
                        pos.dimension()
                            .registry(),
                        pos.dimension()
                            .location(),
                        pos.pos());
                })
                .sorted()
                .findFirst()
                .orElse(-1);
        }
    }

    @Unique
    private static final Class<?> WIRELESS_ACCESS_POINT_MACHINE_CLASS = WirelessAccessPointBlockEntity.class;

    @Redirect(
        method = "add",
        at = @At(
            value = "INVOKE",
            target = "Lcom/google/common/collect/SetMultimap;put(Ljava/lang/Object;Ljava/lang/Object;)Z"))
    private boolean ae2webintegration$trackOwnerAddition(SetMultimap<Class<?>, IGridNode> instance, Object clazz,
        Object gridNode) {
        boolean result = instance.put((Class<?>) clazz, (IGridNode) gridNode);
        if (clazz == WIRELESS_ACCESS_POINT_MACHINE_CLASS) {
            int owner = ((IGridNode) gridNode).getOwningPlayerId();
            if (owner != -1) {
                web$ownerTracker.put(owner, (IGridNode) gridNode);
                GlobalPos pos = ((WirelessAccessPointBlockEntity) ((IGridNode) gridNode).getOwner()).getGlobalPos();
                int hash = Objects.hash(
                    pos.dimension()
                        .registry(),
                    pos.dimension()
                        .location(),
                    pos.pos());
                if (web$hashKey > hash || web$hashKey == -1) {
                    web$hashKey = hash;
                }
            }
        }
        return result;
    }

    @Redirect(
        method = "remove",
        at = @At(
            value = "INVOKE",
            target = "Lcom/google/common/collect/SetMultimap;remove(Ljava/lang/Object;Ljava/lang/Object;)Z"))
    private boolean ae2webintegration$trackOwnerRemoval(SetMultimap<Class<?>, IGridNode> instance, Object clazz,
        Object gridNode) {
        boolean result = instance.remove((Class<?>) clazz, (IGridNode) gridNode);
        if (clazz == WIRELESS_ACCESS_POINT_MACHINE_CLASS) {
            int owner = ((IGridNode) gridNode).getOwningPlayerId();
            if (owner != -1) {
                web$ownerTracker.remove(owner, (IGridNode) gridNode);
                web$updateHashKey();
            }
        }
        return result;
    }

    @Override
    public boolean web$isAvailable() {
        return !web$ownerTracker.isEmpty();
    }

    @Override
    public long web$getSecurityKey() {
        return web$hashKey;
    }

    @Override
    public int web$getOwner() {
        return web$ownerTracker.keySet()
            .stream()
            .sorted()
            .findFirst()
            .orElse(-1);
    }

    @Override
    public GameProfile web$getOwnerProfile() {
        UUID profileID = IPlayerRegistry.getMapping(ServerLifecycleHooks.getCurrentServer())
            .getProfileId(web$getOwner());
        if (profileID == null) {
            return null;
        }
        return ServerLifecycleHooks.getCurrentServer()
            .getProfileCache()
            .get(profileID)
            .orElse(null);
    }

    @Override
    public boolean web$hasPermissions(int playerId) {
        return web$ownerTracker.containsKey(playerId);
    }
}
