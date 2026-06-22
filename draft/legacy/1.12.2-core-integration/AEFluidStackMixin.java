package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.storage.data.IAEFluidStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/**
 * 1.12.2 — NEW: fluid stack mixin (missing on current branch).
 */
@Mixin(value = IAEFluidStack.class, remap = false)
public interface AEFluidStackMixin extends IAEFluidStack, IAEGenericStack, IAEKey {

    @Shadow
    net.minecraftforge.fluids.Fluid getFluid();

    @Override
    default String web$getItemID() {
        return getFluid().getName();
    }

    @Override
    default String web$getDisplayName() {
        return getDisplayName();
    }

    @Override
    default IAEKey web$what() {
        return (IAEKey) this;
    }

    @Override
    default long web$amount() {
        return getStackSize();
    }

    @Override
    default IAEGenericStack web$copy() {
        return (IAEGenericStack) copy();
    }

    @Override
    default boolean web$isFluid() {
        return true;
    }

    @Override
    default boolean web$isCraftable(IAEGrid grid) {
        return isCraftable();
    }

    @Override
    default boolean web$isSameType(IAEKey other) {
        return isSameType((IAEFluidStack) other);
    }
}
