package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.fluids.util.AEFluidStack;
import appeng.util.item.AEItemStack;

/** Native normalization shared by server fallback keys and client capture candidates. */
public final class LegacyIconBaseline {

    private LegacyIconBaseline() {}

    public static @Nullable AEItemStack item(@NotNull AEItemStack stack) {
        Item item = stack.getItem();
        // Fresh construction initializes default Forge capabilities without the original state.
        ItemStack baseline = new ItemStack(item, 1, stack.getItemDamage());
        if (!item.getHasSubtypes() && item.isDamageable()
            && baseline.isItemStackDamageable()
            && item.getMaxDamage(baseline) == item.getMaxDamage(stack.asItemStackRepresentation())) {
            baseline.setItemDamage(0);
        }
        return AEItemStack.fromItemStack(baseline);
    }

    public static @Nullable AEFluidStack fluid(@NotNull AEFluidStack stack) {
        return AEFluidStack.fromFluidStack(new FluidStack(stack.getFluid(), Fluid.BUCKET_VOLUME));
    }
}
