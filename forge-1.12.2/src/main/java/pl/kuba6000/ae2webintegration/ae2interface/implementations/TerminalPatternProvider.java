package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.items.IItemHandler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.glodblock.github.coremod.CoreModHooks;

import appeng.api.config.Settings;
import appeng.api.config.Upgrades;
import appeng.api.config.YesNo;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.parts.AEBasePart;
import appeng.parts.misc.PartInterface;
import appeng.tile.misc.TileInterface;
import appeng.util.item.AEItemStack;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IEncodedPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProvider;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternSlotHandle;

/** Item interfaces selected by the native 1.12 Interface Terminal, including AE2FC's optional extension. */
public final class TerminalPatternProvider implements IPatternProvider {

    private static final int COLUMNS = 9;
    private final IItemHandler inventory;
    private final IGridNode node;
    private final IInterfaceHost host;
    private final DualityInterface duality;

    private TerminalPatternProvider(IGridNode node) {
        this.node = node;
        this.host = (IInterfaceHost) node.getMachine();
        this.duality = host.getInterfaceDuality();
        this.inventory = duality.getPatterns();
    }

    public static @NotNull List<TerminalPatternProvider> snapshot(IGrid grid) {
        List<TerminalPatternProvider> providers = new ArrayList<>();
        collect(grid, TileInterface.class, providers);
        collect(grid, PartInterface.class, providers);
        return providers;
    }

    private static void collect(IGrid grid, Class<? extends IGridHost> type, List<TerminalPatternProvider> providers) {
        IMachineSet machines = Loader.isModLoaded("ae2fc") ? FluidCrafting.machines(grid, type)
            : grid.getMachines(type);
        for (IGridNode node : machines) {
            if (node.isActive()) providers.add(new TerminalPatternProvider(node));
        }
    }

    // Do not resolve classes from AE2FC on servers that only have AE2.
    private static final class FluidCrafting {

        private static IMachineSet machines(IGrid grid, Class<? extends IGridHost> type) {
            return CoreModHooks.getMachines(grid, type);
        }
    }

    @Override
    public @NotNull String web$getName() {
        String terminalName = duality.getTermName();
        String key = terminalName + ".name";
        return I18n.canTranslate(key) ? I18n.translateToLocal(key) : I18n.translateToLocal(terminalName);
    }

    @Override
    public @NotNull DimensionalCoords web$getLocation() {
        return ((IPatternProviderViewable) host).web$getLocation();
    }

    @Override
    public @Nullable String web$getPatternSide() {
        return host instanceof AEBasePart part ? part.getSide()
            .getFacing()
            .getName() : null;
    }

    @Override
    public @Nullable IAEKey web$getPatternIcon() {
        return (IAEKey) (Object) AEItemStack.fromItemStack(
            node.getGridBlock()
                .getMachineRepresentation());
    }

    @Override
    public boolean web$isPatternProviderOnline() {
        return true; // The native terminal enumerates active nodes only.
    }

    @Override
    public boolean web$isPatternProviderVisible() {
        return duality.getConfigManager()
            .getSetting(Settings.INTERFACE_TERMINAL) == YesNo.YES;
    }

    @Override
    public int web$getPatternSlotCount() {
        // The backing inventory has 36 slots even with no expansion cards installed.
        return Math.min(inventory.getSlots(), COLUMNS * (1 + duality.getInstalledUpgrades(Upgrades.PATTERN_EXPANSION)));
    }

    @Override
    public int web$getPatternRowSize() {
        return COLUMNS;
    }

    @Override
    public @NotNull Integer web$getPatternPriority() {
        return duality.getPriority();
    }

    @Override
    public long web$getPatternSortValue() {
        return duality.getSortValue();
    }

    @Override
    public @Nullable IPatternSlotHandle web$getPattern(int slot) {
        ItemStack stack = inventory.getStackInSlot(slot);
        return stack.isEmpty() ? null
            : new Slot(
                stack.copy(),
                host.getTileEntity()
                    .getWorld());
    }

    @Desugar
    private record Slot(ItemStack encoding, World world) implements IPatternSlotHandle {

        @Override
        public boolean web$sameAs(@NotNull IPatternSlotHandle other) {
            return other instanceof Slot slot && ItemStack.areItemStacksEqual(encoding, slot.encoding);
        }

        @Override
        public @Nullable IEncodedPatternDetails web$decode() {
            return encoding.getItem() instanceof ICraftingPatternItem item
                ? (IEncodedPatternDetails) item.getPatternForItem(encoding.copy(), world)
                : null;
        }
    }
}
