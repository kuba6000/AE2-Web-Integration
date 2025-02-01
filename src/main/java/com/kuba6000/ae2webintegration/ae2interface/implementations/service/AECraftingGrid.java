package com.kuba6000.ae2webintegration.ae2interface.implementations.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.minecraft.util.IChatComponent;

import com.google.common.collect.ImmutableSet;
import com.kuba6000.ae2webintegration.ae2interface.implementations.AECraftingCPUCluster;
import com.kuba6000.ae2webintegration.ae2interface.implementations.AECraftingJob;
import com.kuba6000.ae2webintegration.ae2interface.implementations.AEGrid;
import com.kuba6000.ae2webintegration.ae2interface.implementations.IAEObject;
import com.kuba6000.ae2webintegration.ae2interface.implementations.ItemStack;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.PlayerSource;

public class AECraftingGrid extends IAEObject<ICraftingGrid> implements IAECraftingGrid {

    public AECraftingGrid(ICraftingGrid object) {
        super(object);
    }

    @Override
    public int getCPUCount() {
        return get().getCpus()
            .size();
    }

    @Override
    public Set<ICraftingCPUCluster> getCPUs() {
        final ImmutableSet<ICraftingCPU> aecpus = get().getCpus();
        final Set<ICraftingCPUCluster> cpus = new LinkedHashSet<>(aecpus.size());
        int i = 1;
        for (ICraftingCPU cpu : aecpus) {
            cpus.add(new AECraftingCPUCluster((appeng.me.cluster.implementations.CraftingCPUCluster) cpu, i++));
        }
        return cpus;
    }

    public Future<IAECraftingJob> beginCraftingJob(IAEGrid grid, IItemStack craftWhat) {
        PlayerSource actionSrc = ((AEGrid) grid).getPlayerSource();
        final Future<ICraftingJob> job = get().beginCraftingJob(
            actionSrc.player.worldObj,
            ((AEGrid) grid).get(),
            actionSrc,
            ((ItemStack) craftWhat).stack,
            null);
        return new Future<>() {

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return job.cancel(mayInterruptIfRunning);
            }

            @Override
            public boolean isCancelled() {
                return job.isCancelled();
            }

            @Override
            public boolean isDone() {
                return job.isDone();
            }

            @Override
            public IAECraftingJob get() throws InterruptedException, ExecutionException {
                ICraftingJob got = job.get();
                if (got == null) return null;
                return new AECraftingJob(got);
            }

            @Override
            public IAECraftingJob get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
                ICraftingJob got = job.get(timeout, unit);
                if (got == null) return null;
                return new AECraftingJob(got);
            }
        };
    }

    public IChatComponent submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower,
        IAEGrid grid) {
        ICraftingLink link = get().submitJob(
            ((AECraftingJob) job).get(),
            null,
            ((AECraftingCPUCluster) target).get(),
            prioritizePower,
            ((AEGrid) grid).getPlayerSource());
        if (link != null) return null;
        return ((AEGrid) grid).lastFakePlayerChatMessage;
    }
}
