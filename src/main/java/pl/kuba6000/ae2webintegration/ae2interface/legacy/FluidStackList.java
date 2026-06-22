package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import java.util.Iterator;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

public final class FluidStackList implements IStackList {

    private final IItemList<IAEFluidStack> fluids;

    public FluidStackList(IItemList<IAEFluidStack> fluids) {
        this.fluids = fluids;
    }

    @Override
    public long web$getAmount(IAEKey key) {
        IAEFluidStack found = fluids.findPrecise((IAEFluidStack) (Object) key);
        return found == null ? 0L : found.getStackSize();
    }

    @Override
    public Iterable<IAEGenericStack> web$stacks() {
        return () -> {
            Iterator<IAEFluidStack> iterator = fluids.iterator();
            return new Iterator<IAEGenericStack>() {

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public IAEGenericStack next() {
                    return (IAEGenericStack) iterator.next();
                }
            };
        };
    }
}
