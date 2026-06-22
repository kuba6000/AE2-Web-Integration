package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import java.util.Iterator;
import java.util.NoSuchElementException;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/**
 * Legacy adapter: AE2 versions with separate item/fluid storage lists expose a single
 * {@link IStackList} to core.
 */
public final class CompositeStackList implements IStackList {

    private final IStackList items;
    private final IStackList fluids;

    public CompositeStackList(IStackList items, IStackList fluids) {
        this.items = items;
        this.fluids = fluids;
    }

    @Override
    public long web$getAmount(IAEKey key) {
        return key.web$isFluid() ? fluids.web$getAmount(key) : items.web$getAmount(key);
    }

    @Override
    public Iterable<IAEGenericStack> web$stacks() {
        return () -> new Iterator<>() {

            private Iterator<IAEGenericStack> current = items.web$stacks().iterator();
            private boolean onFluids = false;

            @Override
            public boolean hasNext() {
                while (current != null && !current.hasNext()) {
                    if (!onFluids) {
                        onFluids = true;
                        current = fluids.web$stacks().iterator();
                    } else {
                        current = null;
                    }
                }
                return current != null && current.hasNext();
            }

            @Override
            public IAEGenericStack next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }
        };
    }
}
