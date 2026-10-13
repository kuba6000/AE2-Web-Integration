package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import org.jetbrains.annotations.Nullable;

import appeng.api.AEApi;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IStorageMonitorable;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/** Observation-owned listing of every supported storage channel. */
public final class StorageMonitorableStackList implements IStackList {

    private final IStorageMonitorable storage;
    private final Map<IStorageChannel<?>, IItemList<?>> snapshot = new IdentityHashMap<>();

    public StorageMonitorableStackList(IStorageMonitorable storage) {
        this.storage = storage;
        for (IStorageChannel<?> channel : AEApi.instance()
            .storage()
            .storageChannels()) {
            IMEMonitor<?> monitor = monitor(channel);
            if (monitor != null) snapshot.put(channel, enumerate(monitor, channel));
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static IItemList<?> enumerate(IMEMonitor monitor, IStorageChannel channel) {
        // Accepted-amount cache deltas do not prove actual holdings for void storage.
        return monitor.getAvailableItems(channel.createList());
    }

    private @Nullable IItemList<?> list(IMEMonitor<?> monitor) {
        return snapshot.get(monitor.getChannel());
    }

    @Override
    public long web$getAmount(IAEKey key) {
        IMEMonitor<?> monitor = monitor(key);
        if (monitor == null) {
            return 0L;
        }
        IItemList<?> stacks = list(monitor);
        return stacks == null ? 0L : amountIn(stacks, key);
    }

    @Override
    public Iterable<IAEGenericStack> web$stacks() {
        return () -> new Iterator<>() {

            private final Iterator<IStorageChannel<? extends IAEStack<?>>> channels = AEApi.instance()
                .storage()
                .storageChannels()
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
                    if (!channels.hasNext()) {
                        return false;
                    }
                    IMEMonitor<?> monitor = monitor(channels.next());
                    IItemList<?> available = monitor == null ? null : list(monitor);
                    stacks = available == null ? Collections.emptyIterator() : available.iterator();
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
