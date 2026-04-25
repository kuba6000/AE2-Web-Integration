package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.Map;

import net.minecraft.inventory.InventoryCrafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.IInterfaceViewable;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.ae2interface.CraftingMediumTracker;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public class CraftingCPUClusterMixin {

    @Shadow
    private IGrid getGrid() {
        throw new IllegalStateException("Mixin failed to apply");
    }

    @Shadow
    private void postCraftingStatusChange(final IAEStack<?> diff) {
        throw new IllegalStateException("Mixin failed to apply");
    }

    @Inject(method = "postCraftingStatusChange", at = @At("HEAD"))
    void ae2webintegration$postCraftingStatusChange(final IAEStack<?> diff, CallbackInfo ci) {
        IAEMixinCallbacks.getInstance()
            .craftingStatusPostedUpdate((ICraftingCPUCluster) this, (IStack) diff);
    }

    @Inject(method = "completeJob", at = @At("HEAD"))
    void ae2webintegration$completeJob(CallbackInfo ci) {
        IAEMixinCallbacks.getInstance()
            .jobCompleted((IAEGrid) getGrid(), (ICraftingCPUCluster) this);
    }

    @Inject(method = "cancel", at = @At("HEAD"))
    void ae2webintegration$cancel(CallbackInfo ci) {
        IAEMixinCallbacks.getInstance()
            .jobCancelled((IAEGrid) getGrid(), (ICraftingCPUCluster) this);
    }

    @Inject(
        method = "injectItems",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/storage/data/IAEStack;setStackSize(J)Lappeng/api/storage/data/IAEStack;",
            shift = At.Shift.AFTER,
            ordinal = 2))
    void ae2webintegration$fixCpuCluster(CallbackInfoReturnable<IAEStack<?>> cir, @Local(ordinal = 1) IAEStack<?> is) {
        postCraftingStatusChange(is);
    }

    @Redirect(
        method = "executeCrafting",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingMedium;pushPattern(Lappeng/api/networking/crafting/ICraftingPatternDetails;Lnet/minecraft/inventory/InventoryCrafting;)Z"))
    private boolean ae2webintegration$pushPattern(ICraftingMedium medium, ICraftingPatternDetails details,
        InventoryCrafting ic) {
        if (medium.pushPattern(details, ic)) {
            IInterfaceViewable viewable = null;
            Map<ICraftingMedium, IInterfaceViewable> mediumToViewable = CraftingMediumTracker.mediumToViewable
                .get(getGrid());
            if (mediumToViewable != null) {
                viewable = mediumToViewable.get(medium);
            }
            IAEMixinCallbacks.getInstance()
                .pushedPattern(
                    (ICraftingCPUCluster) this,
                    (IPatternProviderViewable) viewable,
                    (IAECraftingPatternDetails) details);
            return true;
        }
        return false;
    }

}
