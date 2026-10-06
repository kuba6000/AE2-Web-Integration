package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.neoforged.neoforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.stacks.AEFluidKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Reads the native data directly because the public fluid conversion creates a stack copy. */
@Mixin(value = AEFluidKey.class, remap = false)
public abstract class AEFluidKeyMetadataMixin implements IAEKey {

    @Shadow
    @Final
    private FluidStack stack;

    @Override
    public @NotNull Integer web$getComponentCount() {
        return stack.getComponents()
            .size();
    }
}
