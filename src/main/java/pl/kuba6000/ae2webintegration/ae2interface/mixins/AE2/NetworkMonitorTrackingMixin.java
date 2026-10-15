package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEStack;
import appeng.me.cache.GridStorageCache;
import appeng.me.cache.NetworkMonitor;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;

@Mixin(value = NetworkMonitor.class, remap = false)
public abstract class NetworkMonitorTrackingMixin<T extends IAEStack<T>> {

    @Shadow
    @Final
    private GridStorageCache myGridCache;

    @Unique
    private int web$transferDepth;

    @WrapMethod(method = "injectItems")
    private T web$captureInput(T input, Actionable mode, IActionSource source, Operation<T> original) {
        StorageCapture capture = ((IAEStorageGrid) myGridCache).web$getStorageCapture();
        if (capture == null || mode != Actionable.MODULATE) {
            return original.call(input, mode, source);
        }
        if (!capture.isProducerThread()) {
            capture.markGap();
            return original.call(input, mode, source);
        }
        if (web$transferDepth != 0) return original.call(input, mode, source);
        long requested = input.getStackSize();
        web$transferDepth++;
        try {
            T remainder = original.call(input, mode, source);
            long accepted = requested - (remainder == null ? 0 : remainder.getStackSize());
            if (accepted > 0) {
                if (input instanceof IAEKey) capture.recordFlow((IAEKey) input, accepted, 0);
                else capture.markGap();
            }
            return remainder;
        } catch (RuntimeException | Error failure) {
            // A provider can throw after changing storage; the missing result cannot prove accepted traffic.
            capture.markGap();
            throw failure;
        } finally {
            web$transferDepth--;
        }
    }

    @WrapMethod(method = "extractItems")
    private T web$captureOutput(T request, Actionable mode, IActionSource source, Operation<T> original) {
        StorageCapture capture = ((IAEStorageGrid) myGridCache).web$getStorageCapture();
        if (capture == null || mode != Actionable.MODULATE) {
            return original.call(request, mode, source);
        }
        if (!capture.isProducerThread()) {
            capture.markGap();
            return original.call(request, mode, source);
        }
        if (web$transferDepth != 0) return original.call(request, mode, source);
        web$transferDepth++;
        try {
            T extracted = original.call(request, mode, source);
            if (extracted != null && extracted.getStackSize() > 0) {
                if (extracted instanceof IAEKey) capture.recordFlow((IAEKey) extracted, 0, extracted.getStackSize());
                else capture.markGap();
            }
            return extracted;
        } catch (RuntimeException | Error failure) {
            // A provider can throw after changing storage; the missing result cannot prove accepted traffic.
            capture.markGap();
            throw failure;
        } finally {
            web$transferDepth--;
        }
    }

    @WrapOperation(
        method = "postChange",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/me/cache/NetworkMonitor;notifyListenersOfChange(Ljava/lang/Iterable;Lappeng/api/networking/security/IActionSource;)V"))
    private void web$changed(NetworkMonitor<T> monitor, Iterable<T> changes, IActionSource source,
        Operation<Void> original) {
        StorageCapture capture = ((IAEStorageGrid) myGridCache).web$getStorageCapture();
        if (capture != null) {
            for (T changed : changes) {
                if (changed instanceof IAEKey) capture.markChanged((IAEKey) changed);
                else if (changed != null) capture.markGap();
            }
        }
        original.call(monitor, changes, source);
    }

    @WrapMethod(method = "setForceUpdate")
    private void web$rebuildScheduled(boolean forceUpdate, Operation<Void> original) {
        original.call(forceUpdate);
        if (!forceUpdate) return;
        StorageCapture capture = ((IAEStorageGrid) myGridCache).web$getStorageCapture();
        if (capture != null) capture.invalidate();
    }

    @Inject(method = "forceUpdate", at = @At("RETURN"))
    private void web$invalidated(CallbackInfo callback) {
        StorageCapture capture = ((IAEStorageGrid) myGridCache).web$getStorageCapture();
        if (capture != null) capture.invalidate();
    }

    @Inject(method = "updateCraftables", at = @At("RETURN"))
    private void web$craftablesChanged(Iterable<T> changes, IActionSource source, CallbackInfo callback) {
        StorageCapture capture = ((IAEStorageGrid) myGridCache).web$getStorageCapture();
        if (capture != null) capture.invalidate();
    }
}
