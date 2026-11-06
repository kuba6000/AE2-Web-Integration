package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageMonitorable;
import appeng.api.storage.data.AEStackTypeRegistry;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/** Live view of every stack type registered with the legacy ME storage service. */
public final class StorageMonitorableStackList implements IStackList {

    private final IStorageMonitorable storage;

    public StorageMonitorableStackList(IStorageMonitorable storage) {
        this.storage = storage;
    }

    @Override
    public long web$getAmount(IAEKey key) {
        IMEMonitor<?> monitor = monitor(key);
        if (monitor == null) {
            return 0L;
        }
        return amountIn(monitor.getStorageList(), key);
    }

    @Override
    public Iterable<IAEGenericStack> web$stacks() {
        return () -> new Iterator<>() {

            private final Iterator<IAEStackType<?>> types = AEStackTypeRegistry.getSortedTypes()
                .iterator();
            private Iterator<?> stacks = Collections.emptyIterator();
            private IAEGenericStack prepared;

            @Override
            public boolean hasNext() {
                if (prepared != null) {
                    return true;
                }
                while (true) {
                    while (stacks.hasNext()) {
                        Object stack = stacks.next();
                        if (stack instanceof IAEGenericStack) {
                            prepared = (IAEGenericStack) stack;
                            return true;
                        }
                    }
                    if (!types.hasNext()) {
                        return false;
                    }
                    IMEMonitor<?> monitor = storage.getMEMonitor(types.next());
                    stacks = monitor == null ? Collections.emptyIterator()
                        : monitor.getStorageList()
                            .iterator();
                }
            }

            @Override
            public IAEGenericStack next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                IAEGenericStack result = prepared;
                prepared = null;
                return result;
            }
        };
    }

    private IMEMonitor<?> monitor(IAEKey key) {
        if (!(key instanceof IAEStack)) {
            return null;
        }
        IAEStackType<?> type = ((IAEStack<?>) (Object) key).getStackType();
        return type == null ? null : storage.getMEMonitor(type);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static long amountIn(IItemList<?> stacks, IAEKey key) {
        IAEStack<?> found = (IAEStack<?>) ((IItemList) stacks).findPrecise((IAEStack) (Object) key);
        return found == null ? 0L : found.getStackSize();
    }
}
