package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import appeng.api.storage.data.IAEFluidStack;
import appeng.fluids.util.AEFluidStack;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyIconBaseline;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(value = IAEFluidStack.class, remap = false)
public interface AEFluidStackMixin extends IAEFluidStack, IAEKey, IAEGenericStack {

    @Override
    default @NotNull ResourceType web$getResourceType() {
        return ResourceType.FLUID;
    }

    @Override
    default @NotNull StableKey web$getKey() {
        return LegacyItemIdentity.encode(this);
    }

    @Override
    default @Nullable StableKey web$getIconBaseKey() {
        IAEFluidStack stack = this;
        if (!(stack instanceof AEFluidStack nativeStack)) return null;
        AEFluidStack baseline = LegacyIconBaseline.fluid(nativeStack);
        return baseline == null ? null : LegacyItemIdentity.encode(baseline);
    }

    @Override
    default @NotNull IAEKey web$copyIdentity() {
        return (IAEKey) LegacyItemIdentity.copy(this);
    }

    @Override
    default @NotNull String web$getItemID() {
        return getFluid().getName();
    }

    @Override
    default @NotNull String web$getDisplayName() {
        return getFluidStack().getLocalizedName();
    }

    @Override
    default boolean web$isCraftable(IAEGrid grid) {
        // Native fluid stacks are display-only on 1.12.2; craft fluid drops as items instead.
        return false;
    }

    @Override
    default @NotNull IAEKey web$what() {
        return this;
    }

    @Override
    default long web$amount() {
        return getStackSize();
    }

}
