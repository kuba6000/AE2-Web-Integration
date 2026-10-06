package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import org.jetbrains.annotations.Nullable;

import com.glodblock.github.common.item.ItemFluidDrop;

import appeng.api.storage.data.IAEItemStack;

/** Keeps optional AE2FC class linkage behind the caller's mod-presence check. */
public final class FluidCraftResources {

    private FluidCraftResources() {}

    public static boolean isFluidDrop(Item item) {
        return item instanceof ItemFluidDrop;
    }

    /** Reads AE2FC's pinned Fluid/FluidTag format without constructing its display item or fluid stack. */
    public static @Nullable Fluid getFluid(IAEItemStack stack) {
        NBTTagCompound tag = (NBTTagCompound) stack.getTagCompound();
        if (tag == null || !tag.hasKey("Fluid", Constants.NBT.TAG_STRING)) return null;
        return FluidRegistry.getFluid(
            tag.getString("Fluid")
                .toLowerCase());
    }

    public static int getComponentCount(IAEItemStack stack) {
        NBTTagCompound tag = (NBTTagCompound) stack.getTagCompound();
        if (tag == null || !tag.hasKey("FluidTag", Constants.NBT.TAG_COMPOUND)) return 0;
        return tag.getCompoundTag("FluidTag")
            .func_150296_c()
            .size();
    }
}
