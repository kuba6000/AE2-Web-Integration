package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.item.Item;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.storage.data.IAEItemStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/**
 * 1.12.2 — item stacks as {@link IAEGenericStack} + {@link IAEKey}. Drops {@code IStack}.
 */
@Mixin(value = IAEItemStack.class, remap = false)
public interface AEItemStackMixin extends IAEItemStack, IAEGenericStack, IAEKey {

    @Shadow
    Item getItem();

    @Shadow
    int getItemDamage();

    @Override
    default String web$getItemID() {
        return getItem().getRegistryName() + ":" + getItemDamage();
    }

    @Override
    default String web$getDisplayName() {
        return asItemStackRepresentation().getDisplayName();
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
        return false;
    }

    @Override
    default boolean web$isCraftable(IAEGrid grid) {
        return isCraftable();
    }

    @Override
    default boolean web$isSameType(IAEKey other) {
        return isSameType((IAEItemStack) other);
    }
}
