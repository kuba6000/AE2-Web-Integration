package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.storage.NetworkStorage;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IStorageCaptureOwner;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;

@Mixin(value = NetworkStorage.class, remap = false)
public class NetworkStorageMixin implements IStorageCaptureOwner {

    @Shadow
    private boolean mountsInUse;

    @Unique
    private @Nullable StorageCapture web$storageCapture;

    @Override
    public void web$setStorageCapture(@Nullable StorageCapture capture) {
        web$storageCapture = capture;
    }

    @WrapMethod(method = "insert")
    private long web$inserted(AEKey key, long requested, Actionable mode, IActionSource source,
        Operation<Long> original) {
        StorageCapture capture = web$storageCapture;
        if (capture == null || mode != Actionable.MODULATE) return original.call(key, requested, mode, source);
        try {
            long accepted = original.call(key, requested, mode, source);
            if (accepted > 0) capture.recordFlow((IAEKey) key, accepted, 0);
            return accepted;
        } catch (RuntimeException | Error failure) {
            // A provider can mutate storage before throwing without reporting the accepted quantity.
            capture.markGap();
            throw failure;
        }
    }

    @WrapMethod(method = "extract")
    private long web$extracted(AEKey key, long requested, Actionable mode, IActionSource source,
        Operation<Long> original) {
        StorageCapture capture = web$storageCapture;
        if (capture == null || mode != Actionable.MODULATE) return original.call(key, requested, mode, source);
        try {
            long extracted = original.call(key, requested, mode, source);
            if (extracted > 0) capture.recordFlow((IAEKey) key, 0, extracted);
            return extracted;
        } catch (RuntimeException | Error failure) {
            capture.markGap();
            throw failure;
        }
    }

    @Inject(method = { "mount", "unmount" }, at = @At("RETURN"))
    private void web$mountsChanged(CallbackInfo ci) {
        // Queued operations are reported when the native operation flushes and actually applies them.
        if (!mountsInUse && web$storageCapture != null) web$storageCapture.invalidateTerminal();
    }
}
