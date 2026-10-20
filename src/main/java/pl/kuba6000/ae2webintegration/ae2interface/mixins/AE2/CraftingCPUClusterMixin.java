package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingMediumTracker;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public class CraftingCPUClusterMixin {

    @Shadow
    private IGrid getGrid() {
        throw new IllegalStateException("Mixin failed to apply");
    }

    @WrapMethod(method = "postCraftingStatusChange")
    private void ae2webintegration$postCraftingStatusChange(IAEItemStack diff, Operation<Void> original) {
        IAEMixinCallbacks.getInstance()
            .craftingStatusPostedUpdate((ICraftingCPUCluster) this, diff);
        original.call(diff);
    }

    @WrapMethod(method = "postChange")
    private void ae2webintegration$postChange(IAEItemStack diff, IActionSource src, Operation<Void> original) {
        IAEMixinCallbacks.getInstance()
            .cpuInventoryChanged((ICraftingCPUCluster) this, (IAEKey) diff);
        original.call(diff, src);
    }

    @WrapMethod(method = "readFromNBT")
    private void ae2webintegration$stateReplaced(NBTTagCompound data, Operation<Void> original) {
        original.call(data);
        IAEMixinCallbacks.getInstance()
            .cpuStateReplaced((ICraftingCPUCluster) this);
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

    @WrapOperation(
        method = "executeCrafting",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingMedium;pushPattern(Lappeng/api/networking/crafting/ICraftingPatternDetails;Lnet/minecraft/inventory/InventoryCrafting;)Z"))
    private boolean ae2webintegration$pushPattern(ICraftingMedium medium, ICraftingPatternDetails details,
        InventoryCrafting ic, Operation<Boolean> original) {
        if (original.call(medium, details, ic)) {
            IPatternProviderViewable viewable = ((ICraftingMediumTracker) ((IAEGrid) getGrid()).web$getCraftingGrid())
                .web$getViewableForCraftingMedium(medium);
            IAEMixinCallbacks.getInstance()
                .pushedPattern((ICraftingCPUCluster) this, viewable, (IAECraftingPatternDetails) details);
            return true;
        }
        return false;
    }

}
