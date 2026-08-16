package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IStorageMonitorable;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;

public final class DispatchingMeInventory implements IAEMeInventoryItem {

    private final IStorageMonitorable storage;

    public DispatchingMeInventory(IStorageMonitorable storage) {
        this.storage = storage;
    }

    @Override
    public long web$extractItems(IAEKey key, long amount, AEActionable mode, IAEGrid grid) {
        IAEMeInventoryItem inventory = channel(key);
        return inventory == null ? 0L : inventory.web$extractItems(key, amount, mode, grid);
    }

    @Override
    public long web$getAvailable(IAEKey key, IAEGrid grid) {
        IAEMeInventoryItem inventory = channel(key);
        return inventory == null ? 0L : inventory.web$getAvailable(key, grid);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private IAEMeInventoryItem channel(IAEKey key) {
        if (!(key instanceof IAEStack)) {
            return null;
        }
        IStorageChannel<?> channel = ((IAEStack<?>) (Object) key).getChannel();
        if (channel == null) {
            return null;
        }
        IMEMonitor<?> monitor = storage.getInventory((IStorageChannel) channel);
        return monitor instanceof IAEMeInventoryItem ? (IAEMeInventoryItem) monitor : null;
    }
}
