package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.authlib.GameProfile;

import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.blockentity.networking.WirelessAccessPointBlockEntity;
import appeng.me.Grid;
import appeng.me.GridNode;
import appeng.me.helpers.PlayerSource;
import appeng.parts.AEBasePart;
import appeng.parts.reporting.AbstractTerminalPart;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPlayerSource;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

@Mixin(value = Grid.class, remap = false)
public abstract class AEGridMixin implements IAEGrid, IGridPlayerSource {

    @Inject(method = "add", at = @At("HEAD"))
    private void web$nodeAdded(GridNode node, @Nullable CompoundTag savedData, CallbackInfo ci) {
        AE.sourcePermissionsChanged(node);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void web$nodeRemoved(GridNode node, CallbackInfo ci) {
        AE.sourcePermissionsChanged(node);
    }

    @Override
    public @NotNull Set<DimensionalCoords> web$getControllers() {
        Set<DimensionalCoords> controllers = new LinkedHashSet<>();
        Grid grid = (Grid) (Object) this;
        for (Class<?> machine : grid.getMachineClasses()) {
            if (ControllerBlockEntity.class.isAssignableFrom(machine)) {
                for (IGridNode node : grid.getMachineNodes(machine)) {
                    BlockPos position = ((ControllerBlockEntity) node.getOwner()).getBlockPos();
                    controllers.add(
                        new DimensionalCoords(
                            node.getLevel()
                                .dimension()
                                .location()
                                .toString(),
                            position.getX(),
                            position.getY(),
                            position.getZ()));
                }
            }
        }
        return controllers;
    }

    @Override
    public @NotNull List<GridAccessSource> web$getAccessSources() {
        List<GridAccessSource> sources = new ArrayList<>();
        Grid grid = (Grid) (Object) this;
        for (Class<?> machine : grid.getMachineClasses()) {
            if (AE.accessSourceKind(machine) == null) continue;
            for (IGridNode node : grid.getMachineNodes(machine)) {
                String kind = AE.accessSourceKind(node);
                if (kind == null) continue;
                PlayerIdentity owner = web$profile(node.getOwningPlayerProfileId());
                if (owner == null) continue;
                Object source = node.getOwner();
                BlockPos position;
                String side = null;
                if (source instanceof AEBasePart part) {
                    position = part.getBlockEntity()
                        .getBlockPos();
                    side = part.getSide()
                        .getName();
                } else if (source instanceof ControllerBlockEntity controller) {
                    position = controller.getBlockPos();
                } else {
                    position = ((WirelessAccessPointBlockEntity) source).getBlockPos();
                }
                sources.add(
                    GridAccessSource.forPlayer(
                        owner,
                        kind,
                        new DimensionalCoords(
                            node.getLevel()
                                .dimension()
                                .location()
                                .toString(),
                            position.getX(),
                            position.getY(),
                            position.getZ()),
                        side,
                        "node_owner"));
            }
        }
        return sources;
    }

    @Override
    public @Nullable PlayerIdentity web$getRepresentativeOwner() {
        List<IGridNode> controllers = new ArrayList<>();
        Grid grid = (Grid) (Object) this;
        for (Class<?> machine : grid.getMachineClasses()) {
            if (ControllerBlockEntity.class.isAssignableFrom(machine)) {
                for (IGridNode node : grid.getMachineNodes(machine)) {
                    controllers.add(node);
                }
            }
        }
        controllers.sort(
            Comparator.comparing(
                (IGridNode node) -> node.getLevel()
                    .dimension()
                    .location()
                    .toString())
                .thenComparingInt(
                    node -> ((ControllerBlockEntity) node.getOwner()).getBlockPos()
                        .getX())
                .thenComparingInt(
                    node -> ((ControllerBlockEntity) node.getOwner()).getBlockPos()
                        .getY())
                .thenComparingInt(
                    node -> ((ControllerBlockEntity) node.getOwner()).getBlockPos()
                        .getZ()));
        for (IGridNode controller : controllers) {
            PlayerIdentity owner = web$profile(controller.getOwningPlayerProfileId());
            if (owner != null) return owner;
        }
        return null;
    }

    @Unique
    private static @Nullable PlayerIdentity web$profile(@Nullable UUID uuid) {
        if (uuid == null) return null;
        var profileCache = ServerLifecycleHooks.getCurrentServer()
            .getProfileCache();
        GameProfile profile = profileCache == null ? null
            : profileCache.get(uuid)
                .orElse(null);
        return new PlayerIdentity(uuid, profile == null ? uuid.toString() : profile.getName());
    }

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

    @Unique
    private Class<?> web$lastUsedMachineClass = null;

    @Override
    public PlayerSource web$getPlayerSource() {
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
            Object o = internalGrid.getPivot()
                .getOwner();
            if (o instanceof IActionHost) actionHost = (IActionHost) o;
            else actionHost = null;
            world = internalGrid.getPivot()
                .getLevel();
        } else {
            actionHost = (IActionHost) terminals.iterator()
                .next();
            world = actionHost.getActionableNode()
                .getLevel();
        }

        PlayerIdentity controllerProfile = AE2Controller.AEControllerProfile;
        if (controllerProfile == null) {
            controllerProfile = new PlayerIdentity(AE2Controller.AEControllerUUID, "AE2CONTROLLER");
        }

        return new PlayerSource(
            FakePlayerFactory.get(world, new GameProfile(controllerProfile.uuid, controllerProfile.name)),
            actionHost);
    }

}
