package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.item.Item;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.storage.data.IAEItemStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

@Mixin(value = IAEItemStack.class, remap = false)
public interface AEItemStackMixin extends IAEItemStack, IAEKey, IStack {

    @Shadow
    Item getItem();

    @Shadow
    int getItemDamage();

    // --- IAEKey ---

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
        return isSameType((IAEItemStack) other);
    }

    // --- IStack ---

    @Override
    default long web$getStackSize() {
        return getStackSize();
    }

    @Override
    default boolean web$isCraftable() {
        return isCraftable();
    }

    @Override
    default long web$getCountRequestable() {
        return getCountRequestable();
    }

    @Override
    default long web$getCountRequestableCrafts() {
        return 0L;
    }

    @Override
    default void web$reset() {
        reset();
    }

    @Override
    default boolean web$isSameType(IStack other) {
        return isSameType((IAEItemStack) other);
    }

    @Override
    default IStack web$copy() {
        return (IStack) copy();
    }

    @Override
    default void web$setStackSize(long size) {
        setStackSize(size);
    }

    @Override
    default boolean web$isItem() {
        return true;
    }

    // --- IAEGenericStack ---
    // web$copy() is provided by IStack.web$copy() with covariant return type

    @Override
    default IAEKey web$what() {
        return this;
    }

    @Override
    default long web$amount() {
        return getStackSize();
    }
}
