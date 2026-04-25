package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Future;

import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;

import com.google.common.collect.ImmutableSet;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.data.IAEItemStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

@Mixin(value = ICraftingGrid.class)
public interface AECraftingGridMixin extends IAECraftingGrid {

    @Override
    public default int web$getCPUCount() {
        return ((ICraftingGrid) (Object) this).getCpus()
            .size();
    }

    @Override
    public default Set<ICraftingCPUCluster> web$getCPUs() {
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
    public default Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IStack stack) {
        PlayerSource actionSrc = (PlayerSource) grid.web$getPlayerSource();
        final Future<ICraftingJob> job = ((ICraftingGrid) (Object) this)
            .beginCraftingJob(actionSrc.player.worldObj, (IGrid) grid, actionSrc, (IAEItemStack) stack, null);
        return (Future<IAECraftingJob>) (Object) job;
    }

    @Override
    public default IChatComponent web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower,
        IAEGrid grid) {
        ICraftingLink link = ((ICraftingGrid) (Object) this).submitJob(
            (ICraftingJob) job,
            null,
            (ICraftingCPU) target,
            prioritizePower,
            (BaseActionSource) grid.web$getPlayerSource());
        if (link != null) return null;
        return grid.web$getLastFakePlayerChatMessage();
    }
}
