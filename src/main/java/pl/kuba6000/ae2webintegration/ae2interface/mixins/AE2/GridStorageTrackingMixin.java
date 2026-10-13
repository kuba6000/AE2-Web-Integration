package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import appeng.me.cache.GridStorageCache;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;

@Mixin(value = GridStorageCache.class, remap = false)
public abstract class GridStorageTrackingMixin implements IAEStorageGrid {

    @Unique
    private @Nullable StorageCapture web$storageCapture;

    @Override
    public @Nullable StorageCapture web$getStorageCapture() {
        return web$storageCapture;
    }

    @Override
    public void web$setStorageCapture(@Nullable StorageCapture capture) {
        web$storageCapture = capture;
    }
}
