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
        int resource = input instanceof IAEKey ? capture.prepareResource((IAEKey) input) : -1;
        web$transferDepth++;
        try {
            T remainder = original.call(input, mode, source);
            long accepted = requested - (remainder == null ? 0 : remainder.getStackSize());
            if (accepted > 0) capture.recordFlow(resource, accepted, 0);
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
        int resource = request instanceof IAEKey ? capture.prepareResource((IAEKey) request) : -1;
        web$transferDepth++;
        try {
            T extracted = original.call(request, mode, source);
            if (extracted != null && extracted.getStackSize() > 0) {
                capture.recordFlow(resource, 0, extracted.getStackSize());
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

    @Inject(
        method = "postChange",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/me/cache/NetworkMonitor;notifyListenersOfChange(Ljava/lang/Iterable;Lappeng/api/networking/security/IActionSource;)V"))
    private void web$changed(boolean added, Iterable<T> changes, IActionSource source, CallbackInfo callback) {
        StorageCapture capture = ((IAEStorageGrid) myGridCache).web$getStorageCapture();
        if (capture == null) return;
        for (T changed : changes) {
            if (changed instanceof IAEKey) capture.markChanged((IAEKey) changed);
            else if (changed != null) capture.markGap();
        }
    }

    @Inject(method = "setForceUpdate", at = @At("RETURN"))
    private void web$rebuildScheduled(boolean forceUpdate, CallbackInfo callback) {
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
