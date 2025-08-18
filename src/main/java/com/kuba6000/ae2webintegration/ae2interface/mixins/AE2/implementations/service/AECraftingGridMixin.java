package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.google.common.collect.ImmutableSet;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumTracker;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.me.helpers.PlayerSource;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;

@Mixin(value = CraftingService.class, remap = false)
public abstract class AECraftingGridMixin implements IAECraftingGrid {

    @Shadow
    @Final
    private NetworkCraftingProviders craftingProviders;

    @Shadow
    public abstract Set<AEKey> getCraftables(AEKeyFilter filter);

    @Override
    public ICraftingMediumTracker web$getCraftingProviders() {
        return (ICraftingMediumTracker) craftingProviders;
    }

    @Override
    public int web$getCPUCount() {
        return ((CraftingService) (Object) this).getCpus()
            .size();
    }

    @Override
    public Set<ICraftingCPUCluster> web$getCPUs() {
        final ImmutableSet<ICraftingCPU> aecpus = ((CraftingService) (Object) this).getCpus();
        final Set<ICraftingCPUCluster> cpus = new LinkedHashSet<>(aecpus.size());
        int i = 1;
        for (ICraftingCPU cpu : aecpus) {
            cpus.add((ICraftingCPUCluster) cpu);
            ((ICraftingCPUCluster) cpu).web$setInternalID(i++);
        }
        return cpus;
    }

    @Override
    public Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey stack, long amount) {
        PlayerSource actionSrc = (PlayerSource) grid.web$getPlayerSource();
        final Future<ICraftingPlan> job = ((CraftingService) (Object) this).beginCraftingCalculation(
            actionSrc.player()
                .get()
                .level(),
            () -> actionSrc,
            (AEKey) stack,
            amount,
            CalculationStrategy.REPORT_MISSING_ITEMS);
        return (Future<IAECraftingJob>) (Object) job;
    }

    @Override
    public String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower, IAEGrid grid) {
        if (target == null) throw new UnsupportedOperationException();
        ICraftingSubmitResult result = ((CraftingService) (Object) this).submitJob(
            (ICraftingPlan) job,
            null,
            (ICraftingCPU) target,
            prioritizePower,
            (IActionSource) grid.web$getPlayerSource());
        if (result.successful()) return null;
        String errorMessage = "";
        switch (result.errorCode()) {
            case INCOMPLETE_PLAN -> errorMessage += "Crafting plan is incomplete.";
            case NO_CPU_FOUND -> errorMessage += "No CPU found for the crafting job.";
            case NO_SUITABLE_CPU_FOUND -> errorMessage += "No suitable CPU found for the crafting job.";
            case CPU_BUSY -> errorMessage += "CPU is busy with another job.";
            case CPU_OFFLINE -> errorMessage += "CPU is offline.";
            case CPU_TOO_SMALL -> errorMessage += "CPU is too small for the crafting job.";
            case MISSING_INGREDIENT -> {
                Object detail = result.errorDetail();
                String detailString = "";
                if (detail instanceof GenericStack) {
                    detailString += ((GenericStack) detail).what()
                        .getId()
                        .toString();
                } else detailString = "UNKNOWN";
                errorMessage += "Ingredient went missing: " + detailString;
            }
            default -> errorMessage += "Unknown error occurred during crafting job submission.";
        }
        return errorMessage;
    }

    @Override
    public Set<IAEKey> web$getCraftables(Function<IAEKey, Boolean> filter) {
        return (Set<IAEKey>) (Object) getCraftables(
            filter == null ? g -> true : (AEKey key) -> filter.apply((IAEKey) key));
    }
}
