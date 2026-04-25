package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemContainer;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

@Mixin(value = IItemList.class, remap = false)
public interface AEItemListMixin<StackType extends IAEStack>
    extends IItemContainer<StackType>, pl.kuba6000.ae2webintegration.core.interfaces.IItemList {

    @Override
    default IStack web$findPrecise(IStack stack) {
        return (IStack) findPrecise((StackType) stack);
    }

}
