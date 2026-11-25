package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEItemKey;
import appeng.helpers.IPriorityHost;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.parts.AEBasePart;
import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IEncodedPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProvider;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternSlotHandle;

/** One active Pattern Access Terminal host, addressed by block and optional multipart face. */
public final class TerminalPatternProvider implements IPatternProvider {

    private static final Set<Class<?>> REPORTED_UNSUPPORTED = new HashSet<>();
    private final IGrid grid;
    private final IGridNode node;
    private final PatternContainer container;
    private final DimensionalCoords location;
    private final @Nullable String side;
    private final PatternContainerGroup group;

    private TerminalPatternProvider(IGrid grid, IGridNode node, PatternContainer container, DimensionalCoords location,
        @Nullable Direction side, PatternContainerGroup group) {
        this.grid = grid;
        this.group = group;
        this.node = node;
        this.container = container;
        this.location = location;
        this.side = side == null ? null : side.getName();
    }

    public static @NotNull Iterable<TerminalPatternProvider> snapshot(IGrid grid) {
        List<IGridNode> nodes = new ArrayList<>();
        Set<PatternContainer> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> type : grid.getMachineClasses()) {
            if (!PatternContainer.class.isAssignableFrom(type)) continue;
            for (IGridNode node : grid.getMachineNodes(type)) {
                if (node.isActive() && seen.add((PatternContainer) node.getOwner())) nodes.add(node);
            }
        }
        // Resolving a group inspects adjacent machines. Do it as the budgeted cursor reaches each host.
        return () -> nodes.stream()
            .map(node -> read(grid, node))
            .filter(Objects::nonNull)
            .iterator();
    }

    private static @Nullable TerminalPatternProvider read(IGrid grid, IGridNode node) {
        if (!node.isActive() || node.getGrid() != grid) return null;
        PatternContainer container = (PatternContainer) node.getOwner();
        DimensionalCoords location = ((IPatternProviderViewable) node).web$getLocation();
        Direction side = location == null ? null : sideOf(node, container, location);
        if (location == null || container instanceof IPart && side == null) {
            reportUnsupported(container, "no stable block or multipart address");
            return null;
        }
        PatternContainerGroup group = container.getTerminalGroup();
        if (group == null) {
            reportUnsupported(container, "no terminal group");
            return null;
        }
        return new TerminalPatternProvider(grid, node, container, location, side, group);
    }

    private static void reportUnsupported(PatternContainer container, String reason) {
        if (REPORTED_UNSUPPORTED.add(container.getClass())) {
            AE2WebIntegration.LOG.warn(
                "Skipping pattern provider of type {}: {}",
                container.getClass()
                    .getName(),
                reason);
        }
    }

    private static @Nullable Direction sideOf(IGridNode node, PatternContainer container, DimensionalCoords location) {
        if (container instanceof AEBasePart part) return part.getSide();
        if (container instanceof IPart && node.getLevel()
            .getBlockEntity(new BlockPos(location.x(), location.y(), location.z())) instanceof IPartHost host) {
            for (Direction side : Direction.values()) {
                if (host.getPart(side) == container) return side;
            }
        }
        return null;
    }

    @Override
    public @NotNull String web$getName() {
        return group.name()
            .getString();
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
        return (IAEKey) (Object) group.icon();
    }

    @Override
    public boolean web$isPatternProviderOnline() {
        return node.isActive() && node.getGrid() == grid;
    }

    @Override
    public boolean web$isPatternProviderVisible() {
        return container.isVisibleInTerminal();
    }

    @Override
    public int web$getPatternSlotCount() {
        return container.getTerminalPatternInventory()
            .size();
    }

    @Override
    public int web$getPatternRowSize() {
        return 9; // PatternAccessTermScreen.COLUMNS
    }

    @Override
    public @Nullable Integer web$getPatternPriority() {
        return container instanceof IPriorityHost priority ? priority.getPriority() : null;
    }

    @Override
    public long web$getPatternSortValue() {
        return container.getTerminalSortOrder();
    }

    @Override
    public @Nullable IPatternSlotHandle web$getPattern(int slot) {
        if (!web$isPatternProviderOnline()) return null; // Do not read a slot after its node moved to another grid.
        InternalInventory inventory = container.getTerminalPatternInventory();
        // An add-on inventory can shrink between the header and this slot.
        if (slot >= inventory.size()) return null;
        AEItemKey encoding = AEItemKey.of(inventory.getStackInSlot(slot));
        return encoding == null ? null : new Slot(encoding, node.getLevel());
    }

    private record Slot(AEItemKey encoding, Level level) implements IPatternSlotHandle {

        @Override
        public boolean web$sameAs(@NotNull IPatternSlotHandle other) {
            return other instanceof Slot slot && encoding.equals(slot.encoding);
        }

        @Override
        public @Nullable IEncodedPatternDetails web$decode() {
            return (IEncodedPatternDetails) PatternDetailsHelper.decodePattern(encoding, level);
        }
    }
}
