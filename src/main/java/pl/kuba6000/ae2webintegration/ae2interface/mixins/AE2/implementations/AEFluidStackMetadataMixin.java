package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.nbt.NBTTagCompound;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.fluids.util.AEFluidStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Reads the native data directly because the public fluid conversion creates a stack copy. */
@Mixin(value = AEFluidStack.class, remap = false)
public abstract class AEFluidStackMetadataMixin implements IAEKey {

    @Shadow
    private @Nullable NBTTagCompound tagCompound;

    @Override
    public @NotNull Integer web$getComponentCount() {
        return tagCompound == null ? 0 : tagCompound.getSize();
    }
}
