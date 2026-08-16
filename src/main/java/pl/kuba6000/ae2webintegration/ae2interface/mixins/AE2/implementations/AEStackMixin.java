package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import cpw.mods.fml.common.registry.GameRegistry;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(IAEStack.class)
public interface AEStackMixin extends IAEStack, IAEGenericStack, IAEKey {

    @Override
    default String web$getItemID() {
        if (this instanceof IAEItemStack) {
            return GameRegistry.findUniqueIdentifierFor(((IAEItemStack) this).getItem()) + ":"
                + ((IAEItemStack) this).getItemDamage();
        }
        if (this instanceof IAEFluidStack) {
            return ((IAEFluidStack) this).getFluid()
                .getName();
        }
        IAEStackType<?> type = getStackType();
        return (type == null ? "unknown" : type.getId()) + ":" + getUnlocalizedName();
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
    default boolean web$isCraftable(IAEGrid grid) {
        return isCraftable();
    }

    @Override
    default boolean web$isSameType(IAEKey other) {
        if (!(other instanceof IAEStack)) {
            return false;
        }
        IAEStack otherStack = (IAEStack) (Object) other;
        if (getStackType() != otherStack.getStackType()) {
            return false;
        }
        return isSameType(otherStack);
    }
}
