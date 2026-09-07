package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;

import appeng.api.storage.data.IAEFluidStack;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(value = IAEFluidStack.class, remap = false)
public interface AEFluidStackMixin extends IAEFluidStack, IAEKey, IAEGenericStack {

    @Override
    default @NotNull StableItemKey web$getStableKey() throws IOException {
        return LegacyItemIdentity.encode(this);
    }

    @Override
    default IAEKey web$copyIdentity() throws IOException {
        return LegacyItemIdentity.copy(this);
    }

    @Override
    default String web$getItemID() {
        return getFluid().getName();
    }

    @Override
    default String web$getDisplayName() {
        return getFluidStack().getLocalizedName();
    }

    @Override
    default boolean web$isCraftable(IAEGrid grid) {
        // Native fluid stacks are display-only on 1.12.2; craft fluid drops as items instead.
        return false;
    }

    @Override
    default boolean web$isSameType(IAEKey other) {
        if (!(other instanceof IAEFluidStack)) {
            return false;
        }
        return getFluid() == ((IAEFluidStack) (Object) other).getFluid();
    }

    @Override
    default IAEKey web$what() {
        return this;
    }

    @Override
    default long web$amount() {
        return getStackSize();
    }

    @Override
    default IAEGenericStack web$copy() {
        return (IAEGenericStack) copy();
    }
}
