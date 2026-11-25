package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import appeng.api.AEApi;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.IInterfaceViewable;
import appeng.helpers.IInterfaceHost;
import appeng.parts.AEBasePart;
import appeng.parts.misc.PartPatternRepeater;
import appeng.util.item.AEItemStack;
import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IEncodedPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProvider;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternSlotHandle;

/** Read-only view of a machine registered with GTNH's interface terminal. */
public final class TerminalPatternProvider implements IPatternProvider {

    private static final Set<Class<?>> UNADDRESSABLE = new HashSet<>();
    // Repeaters expose other grids. A server-wide rebuild stamp covers them without traversing pairs on every poll.
    private static long revision;

    public static void patternsChanged() {
        revision++;
    }

    public static long revision() {
        return revision;
    }

    private final IGridNode node;
    private final IInterfaceViewable machine;
    private final DimensionalCoords location;
    private final @Nullable String side;
    private final String name;
    private final IInventory inventory;
    private final int slotCount;
    private final int rowSize;
    private final WeakReference<World> world;

    private TerminalPatternProvider(IGridNode node, IInterfaceViewable machine, DimensionalCoord location,
        @Nullable ForgeDirection side, String name, IInventory inventory, int slotCount, int rowSize, World world) {
        this.node = node;
        this.machine = machine;
        this.location = new DimensionalCoords(location.getDimension(), location.x, location.y, location.z);
        this.side = side == null ? null
            : side.name()
                .toLowerCase(Locale.ROOT);
        this.name = name;
        this.inventory = inventory;
        this.slotCount = slotCount;
        this.rowSize = rowSize;
        this.world = new WeakReference<>(world);
    }

    public static @NotNull List<TerminalPatternProvider> snapshot(@NotNull IGrid origin) {
        List<TerminalPatternProvider> providers = new ArrayList<>();
        Set<IInterfaceViewable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IGrid> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<IGrid> pending = new ArrayDeque<>();
        pending.add(origin);
        visited.add(origin);
        while (!pending.isEmpty()) {
            IGrid grid = pending.remove();
            collectProviders(grid, seen, providers);
            for (IGridNode node : grid.getMachines(PartPatternRepeater.class)) {
                IGrid remote = linkedGrid(node);
                if (remote != null && visited.add(remote)) pending.add(remote);
            }
        }
        return providers;
    }

    private static void collectProviders(IGrid grid, Set<IInterfaceViewable> seen,
        List<TerminalPatternProvider> providers) {
        for (Class<? extends IInterfaceViewable> type : AEApi.instance()
            .registries()
            .interfaceTerminal()
            .getSupportedClasses()) {
            for (IGridNode node : grid.getMachines(type)) {
                IInterfaceViewable machine = (IInterfaceViewable) node.getMachine();
                if (!seen.add(machine)) continue;
                TerminalPatternProvider provider = describe(node, machine);
                if (provider != null) providers.add(provider);
            }
        }
    }

    private static @Nullable IGrid linkedGrid(IGridNode node) {
        PartPatternRepeater repeater = (PartPatternRepeater) node.getMachine();
        PartPatternRepeater pair = repeater.getPair();
        if (!node.isActive() || !repeater.isProvider() || pair == null || pair.isProvider()) return null;
        IGridNode other = pair.getGridNode();
        if (other == null || !other.isActive()) return null;
        IGrid remote = other.getGrid();
        // A destroyed GTNH node can retain its old grid reference.
        return remote.getMachines(PartPatternRepeater.class)
            .contains(other) ? remote : null;
    }

    private static @Nullable TerminalPatternProvider describe(IGridNode node, IInterfaceViewable machine) {
        DimensionalCoord location = machine.getLocation();
        if (location == null) return skip(machine, "missing block position");
        ForgeDirection side = machine instanceof AEBasePart part ? part.getSide() : null;
        if (machine instanceof AEBasePart && (side == null || side == ForgeDirection.UNKNOWN))
            return skip(machine, "missing multipart face");
        IInventory inventory = machine.getPatterns();
        if (inventory == null) return skip(machine, "missing pattern inventory");
        int slotCount = machine.numSlots();
        int rowSize = machine.rowSize();
        if (rowSize <= 0 || slotCount < 0 || slotCount > inventory.getSizeInventory())
            return skip(machine, "invalid pattern slot layout");
        String name = machine.getName();
        if (name == null) return skip(machine, "missing terminal name");
        World world = node.getWorld();
        if (world == null) return skip(machine, "missing world");
        return new TerminalPatternProvider(node, machine, location, side, name, inventory, slotCount, rowSize, world);
    }

    private static @Nullable TerminalPatternProvider skip(IInterfaceViewable machine, String reason) {
        if (UNADDRESSABLE.add(machine.getClass())) {
            AE2WebIntegration.LOG.warn(
                "Skipping pattern provider of type {}: {}",
                machine.getClass()
                    .getName(),
                reason);
        }
        return null;
    }

    @Override
    public @NotNull String web$getName() {
        return name;
    }

    @Override
    public @NotNull DimensionalCoords web$getLocation() {
        return location;
    }

    @Override
    public @Nullable String web$getPatternSide() {
        return side;
    }

    @Override
    public @Nullable IAEKey web$getPatternIcon() {
        return (IAEKey) (Object) AEItemStack.create(machine.getDisplayRep());
    }

    @Override
    public boolean web$isPatternProviderOnline() {
        return node.isActive();
    }

    @Override
    public boolean web$isPatternProviderVisible() {
        return machine instanceof IInterfaceHost host ? host.getInterfaceDuality()
            .getConfigManager()
            .getSetting(Settings.INTERFACE_TERMINAL) == YesNo.YES : machine.shouldDisplay();
    }

    @Override
    public int web$getPatternSlotCount() {
        return slotCount;
    }

    @Override
    public int web$getPatternRowSize() {
        return rowSize;
    }

    @Override
    public @NotNull Integer web$getPatternPriority() {
        return machine.getPriority();
    }

    @Override
    public long web$getPatternSortValue() {
        // GTNH has no stable numeric order: the GUI breaks display-name ties with a transient packet ID.
        return 0;
    }

    @Override
    public @Nullable IPatternSlotHandle web$getPattern(int slot) {
        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null) return null;
        return new Slot(stack.copy(), world);
    }

    @Desugar
    private record Slot(ItemStack encoding, WeakReference<World> world) implements IPatternSlotHandle {

        @Override
        public boolean web$sameAs(@NotNull IPatternSlotHandle other) {
            return other instanceof Slot slot && ItemStack.areItemStacksEqual(encoding, slot.encoding);
        }

        @Override
        public @Nullable IEncodedPatternDetails web$decode() {
            World level = world.get();
            if (level == null || !(encoding.getItem() instanceof ICraftingPatternItem item)) return null;
            return (IEncodedPatternDetails) item.getPatternForItem(encoding.copy(), level);
        }
    }
}
