package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

import appeng.api.AEApi;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IStorageMonitorable;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/** Live view of every storage channel whose stacks implement the web integration contracts. */
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

            private final Iterator<IStorageChannel<? extends IAEStack<?>>> channels = AEApi.instance()
                .storage()
                .storageChannels()
                .iterator();
            private Iterator<?> stacks = Collections.emptyList()
                .iterator();
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
                    if (!channels.hasNext()) {
                        return false;
                    }
                    IMEMonitor<?> monitor = monitor(channels.next());
                    stacks = monitor == null ? Collections.emptyList()
                        .iterator()
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
        return monitor(((IAEStack<?>) (Object) key).getChannel());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private IMEMonitor<?> monitor(IStorageChannel<?> channel) {
        return channel == null ? null : storage.getInventory((IStorageChannel) channel);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static long amountIn(IItemList<?> stacks, IAEKey key) {
        IAEStack<?> found = (IAEStack<?>) ((IItemList) stacks).findPrecise((IAEStack) (Object) key);
        return found == null ? 0L : found.getStackSize();
    }
}
