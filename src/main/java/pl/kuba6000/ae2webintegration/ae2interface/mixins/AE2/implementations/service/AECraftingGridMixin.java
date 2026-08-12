package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;

import com.google.common.collect.ImmutableSet;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.helpers.PlayerSource;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.GridWorldAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.ChatCapturingPlayerSource;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumTracker;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

@Mixin(value = ICraftingGrid.class)
public interface AECraftingGridMixin extends IAECraftingGrid {

    @Override
    default int web$getCPUCount() {
        return ((ICraftingGrid) (Object) this).getCpus()
            .size();
    }

    @Override
    default Set<ICraftingCPUCluster> web$getCPUs() {
        final ImmutableSet<ICraftingCPU> aecpus = ((ICraftingGrid) (Object) this).getCpus();
        final Set<ICraftingCPUCluster> cpus = new LinkedHashSet<>(aecpus.size());
        int i = 1;
        for (ICraftingCPU cpu : aecpus) {
            cpus.add((ICraftingCPUCluster) cpu);
            ((ICraftingCPUCluster) cpu).web$setInternalID(i++);
        }
        return cpus;
    }

    @Override
    @SuppressWarnings("unchecked")
    default Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey stack, long amount) {
        if (stack.web$isFluid()) {
            throw new UnsupportedOperationException("Native fluid crafting is not supported on AE2 1.12.2");
        }
        PlayerSource actionSrc = (PlayerSource) grid.web$getPlayerSource();
        IAEItemStack itemStack = ((IAEItemStack) (Object) stack).copy();
        itemStack.setStackSize(amount);
        final Future<ICraftingJob> job = ((ICraftingGrid) (Object) this).beginCraftingJob(
            ((GridWorldAccessor) grid).web$getPlayerSourceWorld(),
            (IGrid) grid,
            actionSrc,
            itemStack,
            null);
        return (Future<IAECraftingJob>) (Object) job;
    }

    @Override
    default String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower,
        IAEGrid grid) {
        ChatCapturingPlayerSource source = (ChatCapturingPlayerSource) grid.web$getPlayerSource();
        source.clearLastMessage();
        ICraftingLink link = ((ICraftingGrid) (Object) this)
            .submitJob((ICraftingJob) job, null, (ICraftingCPU) target, prioritizePower, (IActionSource) source);
        String msg = source.takeLastMessage();
        if (link != null) return null;
        return msg == null ? "Submission failed" : msg;
    }

    @Override
    default ICraftingMediumTracker web$getCraftingProviders() {
        throw new UnsupportedOperationException("Use on CraftingGridCache implementation");
    }

    @Override
    default Set<IAEKey> web$getCraftables(Function<IAEKey, Boolean> filter) {
        return Collections.emptySet();
    }
}
