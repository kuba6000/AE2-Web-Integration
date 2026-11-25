package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
import com.google.common.collect.AbstractIterator;

import appeng.api.AEApi;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.IInterfaceViewable;
import appeng.helpers.IInterfaceHost;
import appeng.parts.AEBasePart;
import appeng.parts.misc.PartPatternRepeater;
import appeng.parts.p2p.PartP2PInterface;
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

    /** Both nodes must still be in their original grids. A destroyed GTNH node can retain its old grid reference. */
    @Desugar
    private record Link(PartPatternRepeater source, IMachineSet sourceMachines, PartPatternRepeater target,
        IMachineSet targetMachines) {

        void check() {
            IGridNode from = source.getGridNode();
            IGridNode to = target.getGridNode();
            if (source.getPair() != target || !source.isProvider()
                || target.isProvider()
                || from == null
                || !sourceMachines.contains(from)
                || !from.isActive()
                || to == null
                || !targetMachines.contains(to)
                || !to.isActive()) {
                throw new IllegalStateException("Pattern repeater disconnected during terminal scan");
            }
        }
    }

    @Desugar
    private record Path(IGrid grid, @Nullable Path parent, @Nullable Link link) {

        void check() {
            for (Path path = this; path != null; path = path.parent) {
                if (path.link != null) path.link.check();
            }
        }
    }

    @Desugar
    private record Candidate(Path path, IMachineSet membership, IGridNode node) {}

    private final Candidate candidate;
    private final IInterfaceViewable machine;
    private final DimensionalCoords location;
    private final @Nullable String side;
    private final String name;
    private final IInventory inventory;
    private final int slotCount;
    private final int rowSize;
    private final WeakReference<World> world;

    private TerminalPatternProvider(Candidate candidate, IInterfaceViewable machine, DimensionalCoord location,
        @Nullable ForgeDirection side, String name, IInventory inventory, int slotCount, int rowSize, World world) {
        this.candidate = candidate;
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

    public static @NotNull Iterable<TerminalPatternProvider> snapshot(@NotNull IGrid origin) {
        List<Candidate> nodes = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        Set<IInterfaceViewable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IGrid> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Path> pending = new ArrayDeque<>();
        visited.add(origin);
        pending.add(new Path(origin, null, null));
        while (!pending.isEmpty()) {
            Path path = pending.remove();
            IGrid grid = path.grid;
            paths.add(path);
            for (Class<? extends IInterfaceViewable> type : AEApi.instance()
                .registries()
                .interfaceTerminal()
                .getSupportedClasses()) {
                IMachineSet membership = grid.getMachines(type);
                for (IGridNode node : membership) {
                    if (seen.add((IInterfaceViewable) node.getMachine()))
                        nodes.add(new Candidate(path, membership, node));
                }
            }
            IMachineSet repeaters = grid.getMachines(PartPatternRepeater.class);
            for (IGridNode node : repeaters) {
                PartPatternRepeater repeater = (PartPatternRepeater) node.getMachine();
                PartPatternRepeater pair = repeater.getPair();
                if (!repeater.isProvider() || pair == null || !node.isActive() || pair.isProvider()) continue;
                IGridNode other = pair.getGridNode();
                if (other == null || !other.isActive()) continue;
                IGrid reachable = other.getGrid();
                if (visited.add(reachable)) {
                    Link link = new Link(repeater, repeaters, pair, reachable.getMachines(PartPatternRepeater.class));
                    pending.add(new Path(reachable, path, link));
                }
            }
        }
        return () -> new AbstractIterator<TerminalPatternProvider>() {

            final Iterator<Candidate> candidates = nodes.iterator();
            final List<TerminalPatternProvider> tunnels = new ArrayList<>();

            @Override
            protected TerminalPatternProvider computeNext() {
                while (candidates.hasNext()) {
                    Candidate candidate = candidates.next();
                    candidate.path.check();
                    if (!candidate.membership.contains(candidate.node)) continue;
                    TerminalPatternProvider row = describe(candidate);
                    if (row != null) {
                        if (row.machine instanceof PartP2PInterface) tunnels.add(row);
                        return row;
                    }
                }
                // The core publishes only after this final hasNext. Discard old rows if a directed link vanished.
                for (Path path : paths) {
                    if (path.link != null) path.link.check();
                }
                for (TerminalPatternProvider tunnel : tunnels) tunnel.currentInventory();
                return endOfData();
            }
        };
    }

    private static @Nullable TerminalPatternProvider describe(Candidate candidate) {
        IInterfaceViewable machine = (IInterfaceViewable) candidate.node.getMachine();
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
        World world = candidate.node.getWorld();
        if (world == null) return skip(machine, "missing world");
        return new TerminalPatternProvider(
            candidate,
            machine,
            location,
            side,
            name,
            inventory,
            slotCount,
            rowSize,
            world);
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
        return candidate.node.isActive();
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

    /** An output P2P interface can retarget an entirely different pattern inventory between server ticks. */
    private @Nullable IInventory currentInventory() {
        if (!candidate.membership.contains(candidate.node)) {
            throw new IllegalStateException("Pattern provider removed during terminal scan");
        }
        IInventory current = machine.getPatterns();
        if (machine instanceof PartP2PInterface && current != inventory) {
            throw new IllegalStateException("Pattern tunnel changed its source during terminal scan");
        }
        return current;
    }

    @Override
    public @Nullable IPatternSlotHandle web$getPattern(int slot) {
        candidate.path.check();
        IInventory current = currentInventory();
        if (current == null || slot >= current.getSizeInventory()) return null;
        ItemStack stack = current.getStackInSlot(slot);
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
