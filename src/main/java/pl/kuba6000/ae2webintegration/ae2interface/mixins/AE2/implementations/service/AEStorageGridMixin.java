package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.stacks.AEKey;
import appeng.me.service.StorageService;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IStorageCaptureOwner;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;

@Mixin(value = StorageService.class, remap = false)
public class AEStorageGridMixin implements IAEStorageGrid {

    @Shadow
    private boolean cachedStacksNeedUpdate;

    @Unique
    private @Nullable StorageCapture web$storageCapture;

    @Override
    public IStackList web$getStorageList() {
        return (IStackList) (Object) ((StorageService) (Object) this).getCachedInventory();
    }

    @Override
    public IStackList web$getStorageSnapshot() {
        // A reconciliation must not reuse a cache sampled before the requested boundary.
        cachedStacksNeedUpdate = true;
        return web$getStorageList();
    }

    @Override
    public IAEMeInventoryItem web$getInventory() {
        return (IAEMeInventoryItem) ((StorageService) (Object) this).getInventory();
    }

    @Override
    public @Nullable StorageCapture web$getStorageCapture() {
        return web$storageCapture;
    }

    @Override
    public void web$setStorageCapture(@Nullable StorageCapture capture) {
        web$storageCapture = capture;
        ((IStorageCaptureOwner) ((StorageService) (Object) this).getInventory()).web$setStorageCapture(capture);
    }

    @Inject(method = "postWatcherUpdate", at = @At("HEAD"))
    private void web$absoluteChanged(AEKey key, long amount, CallbackInfo ci) {
        if (web$storageCapture != null) web$storageCapture.absoluteChanged((IAEKey) key, amount);
    }

    @WrapMethod(method = "updateCachedStacks")
    private void web$captureRefresh(Operation<Void> original) {
        StorageCapture capture = web$storageCapture;
        if (capture == null) {
            original.call();
            return;
        }
        capture.beginAbsoluteChanges();
        boolean success = false;
        try {
            original.call();
            success = true;
        } finally {
            // Native code clears the dirty flag before scanning; do not retain a failed partial scan.
            if (!success) cachedStacksNeedUpdate = true;
            capture.endAbsoluteChanges(success);
        }
    }
}
