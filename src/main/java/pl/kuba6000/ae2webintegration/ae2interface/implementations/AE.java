package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraftforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;

import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.blockentity.networking.WirelessAccessPointBlockEntity;
import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;
import appeng.parts.AEBasePart;
import appeng.parts.reporting.AbstractTerminalPart;
import appeng.parts.reporting.PatternAccessTerminalPart;
import pl.kuba6000.ae2webintegration.core.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

public class AE implements IAE {

    public static WeakHashMap<ICraftingCPUCluster, Integer> cpuInternalIDMap = new WeakHashMap<>();

    public static AE instance = new AE();

    public static void sourcePermissionsChanged(IGridNode node) {
        if (accessSourceKind(node) != null) GridAccessSessions.permissionsChanged();
    }

    public static @Nullable String accessSourceKind(Class<?> ownerType) {
        if (ControllerBlockEntity.class.isAssignableFrom(ownerType)) return "controller";
        if (WirelessAccessPointBlockEntity.class.isAssignableFrom(ownerType)) return "wireless_access_point";
        if (AbstractTerminalPart.class.isAssignableFrom(ownerType)
            || PatternAccessTerminalPart.class.isAssignableFrom(ownerType)) return "terminal";
        return null;
    }

    public static @Nullable String accessSourceKind(IGridNode node) {
        Object owner = node.getOwner();
        String kind = accessSourceKind(owner.getClass());
        if (kind == null) return null;
        // Only the terminal's main node grants access on a multipart host.
        if (owner instanceof AEBasePart part && node != part.getGridNode()) return null;
        return kind;
    }

    @Override
    public Iterable<IAEGrid> web$getGrids() {
        Set<IAEGrid> grids = new LinkedHashSet<>();
        for (Grid grid : TickHandler.instance()
            .getGridList()) {
            if (!grid.isEmpty()) grids.add((IAEGrid) grid);
        }
        return grids;
    }

    @Override
    public IStackList web$createStackList() {
        return (IStackList) (Object) new KeyCounter();
    }

    @Override
    public IAEGenericStack web$stackOf(IAEKey key, long amount) {
        return (IAEGenericStack) (Object) new GenericStack((AEKey) key, amount);
    }

    @Override
    public IAEPlayerData web$getPlayerData() {
        return (IAEPlayerData) IPlayerRegistry.getMapping(ServerLifecycleHooks.getCurrentServer());
    }
}
