package pl.kuba6000.ae2webintegration.ae2interface.mixins.accessors;

import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.fluids.util.AEFluidStack;

@Mixin(value = AEFluidStack.class, remap = false)
public interface IdentityAEFluidStackAccessor {

    @Accessor("tagCompound")
    NBTTagCompound web$getIdentityTag();
}
