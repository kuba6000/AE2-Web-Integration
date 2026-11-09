package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.AEStackTypeRegistry;
import appeng.api.storage.data.IAEStackType;
import appeng.me.cache.GridStorageCache;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IStorageCacheRefresh;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;

@Mixin(value = GridStorageCache.class, remap = false)
public abstract class GridStorageTrackingMixin implements IAEStorageGrid {

    @Unique
    private @Nullable StorageCapture web$storageCapture;

    @Override
    public IStackList web$getStorageSnapshot() {
        GridStorageCache storage = (GridStorageCache) (Object) this;
        for (IAEStackType<?> type : AEStackTypeRegistry.getSortedTypes()) {
            IMEMonitor<?> monitor = storage.getMEMonitor(type);
            if (monitor != null) ((IStorageCacheRefresh) monitor).web$refreshStorageCache();
        }
        return web$getStorageList();
    }

    @Override
    public @Nullable StorageCapture web$getStorageCapture() {
        return web$storageCapture;
    }

    @Override
    public void web$setStorageCapture(@Nullable StorageCapture capture) {
        web$storageCapture = capture;
    }
}
