package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingMediumTracker;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IExecutingCraftingJobAccessor;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public class CraftingCPULogicMixin implements ICraftingCPULogicAccessor {

    @Final
    @Shadow
    CraftingCPUCluster cluster;

    @Shadow
    private ExecutingCraftingJob job;

    @Inject(method = "trySubmitJob", at = @At("RETURN"))
    void ae2webintegration$onJobSubmit(IGrid grid, ICraftingPlan plan, IActionSource src,
        @Nullable ICraftingRequester requester, CallbackInfoReturnable<ICraftingSubmitResult> ci) {
        if (ci.getReturnValue()
            .successful()) {
            Player player = src.player()
                .orElse(null);
            PlayerIdentity nativeRequester = player == null ? null
                : new PlayerIdentity(
                    player.getUUID(),
                    player.getGameProfile()
                        .getName());
            IAEMixinCallbacks.getInstance()
                .jobStarted(
                    (ICraftingCPUCluster) (Object) cluster,
                    (IAEGrid) grid,
                    false,
                    player == null,
                    nativeRequester);
        }
    }

    @WrapMethod(method = "postChange")
    private void ae2webintegration$postCraftingStatusChange(AEKey diff, Operation<Void> original) {
        IAEMixinCallbacks.getInstance()
            .craftingStatusPostedUpdate((ICraftingCPUCluster) (Object) cluster, (IAEKey) diff);
        original.call(diff);
    }

    @WrapMethod(method = "readFromNBT")
    private void web$restoreState(CompoundTag data, HolderLookup.Provider registries, Operation<Void> original) {
        original.call(data, registries);
        IAEMixinCallbacks.getInstance()
            .cpuStateReplaced((ICraftingCPUCluster) (Object) cluster);
    }

    @WrapMethod(method = "setJobSuspended")
    private void web$setJobSuspended(boolean suspended, Operation<Void> original) {
        CraftingCpuLogic logic = (CraftingCpuLogic) (Object) this;
        boolean previous = logic.isJobSuspended();
        original.call(suspended);
        if (previous != logic.isJobSuspended()) IAEMixinCallbacks.getInstance()
            .cpuMetadataChanged((ICraftingCPUCluster) (Object) cluster);
    }

    @Inject(method = "finishJob", at = @At("HEAD"))
    void ae2webintegration$finishJob(boolean success, CallbackInfo ci) {
        if (success) IAEMixinCallbacks.getInstance()
            .jobCompleted((IAEGrid) cluster.getGrid(), (ICraftingCPUCluster) (Object) cluster);
        else IAEMixinCallbacks.getInstance()
            .jobCancelled((IAEGrid) cluster.getGrid(), (ICraftingCPUCluster) (Object) cluster);
    }

    @Redirect(
        method = "executeCrafting",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean ae2webintegration$pushPattern(ICraftingProvider medium, IPatternDetails details, KeyCounter[] ic) {
        if (medium.pushPattern(details, ic)) {
            IPatternProviderViewable viewable = ((ICraftingMediumTracker) cluster.getGrid()
                .getService(ICraftingService.class)).web$getViewableForCraftingMedium(medium);
            IAEMixinCallbacks.getInstance()
                .pushedPattern((ICraftingCPUCluster) (Object) cluster, viewable, (IAECraftingPatternDetails) details);
            return true;
        }
        return false;
    }

    @Override
    public IExecutingCraftingJobAccessor web$getJob() {
        return (IExecutingCraftingJobAccessor) job;
    }
}
