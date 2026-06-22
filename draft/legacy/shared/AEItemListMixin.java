package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemContainer;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/**
 * GTNH / 1.12.2 — legacy {@link IItemList} cast to {@link IStackList}.
 */
@Mixin(value = IItemList.class, remap = false)
public interface AEItemListMixin<StackType extends IAEStack>
    extends IItemContainer<StackType>, IStackList {

    @Override
    @SuppressWarnings("unchecked")
    default long web$getAmount(IAEKey key) {
        StackType found = findPrecise((StackType) (Object) key);
        return found == null ? 0L : found.getStackSize();
    }

    @Override
    @SuppressWarnings("unchecked")
    default Iterable<IAEGenericStack> web$stacks() {
        return () -> ((java.util.Iterator<IAEGenericStack>) (java.util.Iterator<?>) ((IItemList<StackType>) this)
            .iterator());
    }
}
