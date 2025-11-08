package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import cpw.mods.fml.common.registry.GameRegistry;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

@Mixin(IAEStack.class)
public interface AEStackMixin extends IAEStack, IStack {

    @Override
    public default String web$getItemID() {
        if (isItem()) return GameRegistry.findUniqueIdentifierFor(((IAEItemStack) this).getItem())
            .toString() + ":"
            + ((IAEItemStack) this).getItemDamage();
        return ((IAEFluidStack) this).getFluid()
            .getName();
    }

    @Override
    public default String web$getDisplayName() {
        return getDisplayName();
    }

    @Override
    public default long web$getStackSize() {
        return getStackSize();
    }

    @Override
    public default boolean web$isCraftable() {
        return isCraftable();
    }

    @Override
    public default long web$getCountRequestable() {
        return getCountRequestable();
    }

    @Override
    public default long web$getCountRequestableCrafts() {
        return getCountRequestableCrafts();
    }

    @Override
    public default void web$reset() {
        reset();
    }

    @Override
    public default boolean web$isSameType(IStack other) {
        return isSameType(other);
    }

    @Override
    public default IStack web$copy() {
        return (IStack) copy();
    }

    @Override
    public default void web$setStackSize(long size) {
        setStackSize(size);
    }
}
