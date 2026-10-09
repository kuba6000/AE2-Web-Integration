package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;

/** Fresh native fallback stacks; original tags are neither retained nor modified. */
public final class LegacyIconBaseline {

    private LegacyIconBaseline() {}

    public static @NotNull ItemStack item(@NotNull ItemStack source) {
        Item item = source.getItem();
        ItemStack baseline = new ItemStack(item, 1, source.getItemDamage());
        // Preserve metadata whenever the native subtype/durability declarations disagree.
        if (!item.getHasSubtypes() && item.isDamageable()
            && baseline.isItemStackDamageable()
            && item.getMaxDamage(baseline) == item.getMaxDamage(source)) {
            baseline.setItemDamage(0);
        }
        return baseline;
    }

    public static @NotNull FluidStack fluid(@NotNull FluidStack source) {
        return new FluidStack(source.getFluid(), FluidContainerRegistry.BUCKET_VOLUME);
    }
}
