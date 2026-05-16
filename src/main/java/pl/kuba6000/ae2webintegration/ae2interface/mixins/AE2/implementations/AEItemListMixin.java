package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemContainer;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

@Mixin(value = IItemList.class, remap = false)
public interface AEItemListMixin<T extends IAEStack<T>>
    extends IItemContainer<T>, pl.kuba6000.ae2webintegration.core.interfaces.IItemList {

    @Override
    default IStack web$findPrecise(IStack stack) {
        return (IStack) findPrecise((T) stack);
    }

    @Override
    default long web$findPrecise(IAEKey stack) {
        // IAEKey instances on 1.12.2 are always IAEItemStack (via mixin), so the cast is safe
        T found = findPrecise((T) (Object) stack);
        if (found instanceof IAEItemStack) {
            return ((IAEItemStack) found).getStackSize();
        }
        return 0L;
    }
}
