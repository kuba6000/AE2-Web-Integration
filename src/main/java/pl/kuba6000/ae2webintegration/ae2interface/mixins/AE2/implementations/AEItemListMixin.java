package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemContainer;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemStack;

@Mixin(value = IItemList.class, remap = false)
public interface AEItemListMixin<StackType extends IAEStack>
    extends IItemContainer<StackType>, pl.kuba6000.ae2webintegration.core.interfaces.IItemList {

    @Override
    default IItemStack web$findPrecise(IItemStack stack) {
        return (IItemStack) findPrecise((StackType) stack);
    }

}
