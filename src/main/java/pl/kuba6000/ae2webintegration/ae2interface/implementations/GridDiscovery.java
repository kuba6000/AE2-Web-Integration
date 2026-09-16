package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.util.DimensionalCoord;
import appeng.core.worlddata.WorldData;
import appeng.me.Grid;
import appeng.parts.reporting.AbstractPartTerminal;
import appeng.tile.networking.TileController;
import appeng.tile.networking.TileWireless;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IPlayerProfileLookup;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSource;

/** Server-thread discovery using current AE2 node ownership and explicit player security permissions. */
public final class GridDiscovery {

    private static final Comparator<IGridNode> CONTROLLER_ORDER = Comparator
        .comparingInt((IGridNode node) -> node.getWorld().provider.getDimension())
        .thenComparingInt(
            node -> node.getGridBlock()
                .getLocation().x)
        .thenComparingInt(
            node -> node.getGridBlock()
                .getLocation().y)
        .thenComparingInt(
            node -> node.getGridBlock()
                .getLocation().z);

    private GridDiscovery() {}

    public static @Nullable String accessSourceKind(@NotNull Class<?> machineType) {
        if (TileController.class.isAssignableFrom(machineType)) return "controller";
        if (TileWireless.class.isAssignableFrom(machineType)) return "wireless_access_point";
        if (AbstractPartTerminal.class.isAssignableFrom(machineType)) return "terminal";
        if (ISecurityProvider.class.isAssignableFrom(machineType)) return "security_terminal";
        return null;
    }

    public static @NotNull Set<DimensionalCoords> controllers(@NotNull Grid grid) {
        Set<DimensionalCoords> controllers = new LinkedHashSet<>();
        for (Class<? extends IGridHost> machineType : grid.getMachineClasses()) {
            if (!TileController.class.isAssignableFrom(machineType)) continue;
            for (IGridNode node : grid.getMachines(machineType)) {
                controllers.add(position(node));
            }
        }
        return controllers;
    }

    public static @Nullable GridAccessSource ownerSource(@NotNull IGridNode node) {
        Object machine = node.getMachine();
        String kind = accessSourceKind(machine.getClass());
        if (kind == null || machine instanceof ISecurityProvider) return null;
        PlayerIdentity owner = players().web$getPlayerProfile(node.getPlayerID());
        if (owner == null) return null;
        String side = machine instanceof AbstractPartTerminal terminal ? terminal.getSide()
            .name() : null;
        return new GridAccessSource(owner, kind, position(node), side, "node_owner");
    }

    public static @NotNull List<GridAccessSource> securitySources(@NotNull IGridNode node) {
        List<GridAccessSource> sources = new ArrayList<>();
        ISecurityProvider provider = (ISecurityProvider) node.getMachine();
        IPlayerProfileLookup players = players();
        HashMap<Integer, EnumSet<SecurityPermissions>> permissions = new HashMap<>();
        provider.readPermissions(permissions);
        DimensionalCoords position = position(node);
        int owner = provider.getOwner();
        for (Map.Entry<Integer, EnumSet<SecurityPermissions>> entry : permissions.entrySet()) {
            int playerId = entry.getKey();
            // Unassigned cards grant no web access, regardless of AE2's default permissions.
            if (playerId < 0 || !hasWebPermissions(entry.getValue())) continue;
            PlayerIdentity player = players.web$getPlayerProfile(playerId);
            if (player != null) {
                String reason = playerId == owner ? "security_owner" : "security_card";
                sources.add(new GridAccessSource(player, "security_terminal", position, null, reason));
            }
        }
        return sources;
    }

    private static boolean hasWebPermissions(@NotNull EnumSet<SecurityPermissions> permissions) {
        return permissions.contains(SecurityPermissions.BUILD) && permissions.contains(SecurityPermissions.EXTRACT)
            && permissions.contains(SecurityPermissions.INJECT)
            && permissions.contains(SecurityPermissions.CRAFT);
    }

    public static @Nullable PlayerIdentity representativeOwner(@NotNull Grid grid) {
        IPlayerProfileLookup players = players();
        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        if (security.isAvailable()) {
            PlayerIdentity owner = players.web$getPlayerProfile(security.getOwner());
            if (owner != null) return owner;
        }
        IGridNode first = null;
        PlayerIdentity owner = null;
        for (Class<? extends IGridHost> machineType : grid.getMachineClasses()) {
            if (!TileController.class.isAssignableFrom(machineType)) continue;
            for (IGridNode node : grid.getMachines(machineType)) {
                PlayerIdentity candidate = players.web$getPlayerProfile(node.getPlayerID());
                if (candidate != null && (first == null || CONTROLLER_ORDER.compare(node, first) < 0)) {
                    first = node;
                    owner = candidate;
                }
            }
        }
        return owner;
    }

    private static @NotNull DimensionalCoords position(@NotNull IGridNode node) {
        DimensionalCoord location = node.getGridBlock()
            .getLocation();
        return new DimensionalCoords(node.getWorld().provider.getDimension(), location.x, location.y, location.z);
    }

    private static @NotNull IPlayerProfileLookup players() {
        return (IPlayerProfileLookup) WorldData.instance()
            .playerData();
    }
}
