package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.item.Item;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.storage.data.IAEItemStack;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(value = IAEItemStack.class, remap = false)
public interface AEItemStackMixin extends IAEItemStack, IAEKey, IAEGenericStack {

    @Override
    default @NotNull StableKey web$getKey() {
        return LegacyItemIdentity.encode(this);
    }

    @Override
    default IAEKey web$copyIdentity() {
        return LegacyItemIdentity.copy(this);
    }

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
    default boolean web$isCraftable(IAEGrid grid) {
        return isCraftable();
    }

    @Override
    default boolean web$isSameType(IAEKey other) {
        if (!(other instanceof IAEItemStack)) {
            return false;
        }
        return isSameType((IAEItemStack) (Object) other);
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
