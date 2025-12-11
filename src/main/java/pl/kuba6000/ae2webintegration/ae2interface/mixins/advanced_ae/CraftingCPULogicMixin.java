package pl.kuba6000.ae2webintegration.ae2interface.mixins.advanced_ae;

import java.util.Map;

import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IExecutingCraftingJobAccessor;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

@Mixin(value = AdvCraftingCPULogic.class, remap = false)
public abstract class CraftingCPULogicMixin implements ICraftingCPULogicAccessor {

    @Final
    @Shadow
    AdvCraftingCPU cpu;

    @Shadow
    private ExecutingCraftingJob job;

    @Inject(method = "trySubmitJob", at = @At("RETURN"))
    void ae2webintegration$onJobSubmit(IGrid grid, ICraftingPlan plan, IActionSource src,
        @Nullable ICraftingRequester requester, CallbackInfoReturnable<ICraftingSubmitResult> ci) {
        if (ci.getReturnValue()
            .successful()) {
            boolean isMachine = !src.player()
                .isPresent();
            IAEMixinCallbacks.getInstance()
                .jobStarted(
                    (ICraftingCPUCluster) (Object) cpu,
                    (IAECraftingGrid) grid.getCraftingService(),
                    (IAEGrid) grid,
                    false,
                    !isMachine);
        }
    }

    @Inject(method = "postChange", at = @At("HEAD"))
    void ae2webintegration$postCraftingStatusChange(AEKey diff, CallbackInfo ci) {
        IAEMixinCallbacks.getInstance()
            .craftingStatusPostedUpdate((ICraftingCPUCluster) (Object) cpu, (IAEKey) diff);
    }

    @Inject(method = "finishJob", at = @At("HEAD"))
    void ae2webintegration$finishJob(boolean success, CallbackInfo ci) {
        if (success) IAEMixinCallbacks.getInstance()
            .jobCompleted((IAEGrid) cpu.getGrid(), (ICraftingCPUCluster) (Object) cpu);
        else IAEMixinCallbacks.getInstance()
            .jobCancelled((IAEGrid) cpu.getGrid(), (ICraftingCPUCluster) (Object) cpu);
    }

    @Redirect(
        method = "executeCrafting",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean ae2webintegration$pushPattern(ICraftingProvider medium, IPatternDetails details, KeyCounter[] ic) {
        if (medium.pushPattern(details, ic)) {
            IGridNode viewable = null;
            Map<ICraftingProvider, IGridNode> mediumToViewable = ((IAECraftingGrid) cpu.getGrid()
                .getService(ICraftingService.class)).web$getCraftingProviders()
                .web$getCraftingMediums();
            if (mediumToViewable != null) {
                viewable = mediumToViewable.get(medium);
            }
            IAEMixinCallbacks.getInstance()
                .pushedPattern(
                    (ICraftingCPUCluster) (Object) cpu,
                    (IPatternProviderViewable) viewable,
                    (IAECraftingPatternDetails) details);
            return true;
        }
        return false;
    }

    @Override
    public IExecutingCraftingJobAccessor web$getJob() {
        return (IExecutingCraftingJobAccessor) job;
    }
}
