package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Function;

import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import org.spongepowered.asm.mixin.Mixin;

import com.google.common.collect.ImmutableSet;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import pl.kuba6000.ae2webintegration.ae2interface.CraftingMediumTracker;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumTracker;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;
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
    default Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IStack stack) {
        WorldServer world = FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getWorld(0);
        final Future<ICraftingJob> job = ((ICraftingGrid) (Object) this).beginCraftingJob(
            world,
            (IGrid) grid,
            (IActionSource) grid.web$getPlayerSource(),
            (IAEItemStack) stack,
            null);
        return (Future<IAECraftingJob>) (Object) job;
    }

    @Override
    default Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey stack, long amount) {
        IAEItemStack template = (IAEItemStack) stack;
        IAEItemStack stackWithAmount = template.copy();
        stackWithAmount.setStackSize(amount);
        WorldServer world = FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getWorld(0);
        final Future<ICraftingJob> job = ((ICraftingGrid) (Object) this)
            .beginCraftingJob(world, (IGrid) grid, (IActionSource) grid.web$getPlayerSource(), stackWithAmount, null);
        return (Future<IAECraftingJob>) (Object) job;
    }

    @Override
    default String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower,
        IAEGrid grid) {
        ICraftingLink link = ((ICraftingGrid) (Object) this).submitJob(
            (ICraftingJob) job,
            null,
            (ICraftingCPU) target,
            prioritizePower,
            (IActionSource) grid.web$getPlayerSource());
        return link != null ? null : "Submission failed";
    }

    @Override
    default ICraftingMediumTracker web$getCraftingProviders() {
        return CraftingMediumTracker.INSTANCE;
    }

    @Override
    default Set<IAEKey> web$getCraftables(Function<IAEKey, Boolean> filter) {
        // 1.12.2 AE2UEL does not expose getMediums() on ICraftingGrid.
        // Iterate CPUs and collect their final outputs as available craftables.
        Set<IAEKey> result = new LinkedHashSet<>();
        for (ICraftingCPU cpu : ((ICraftingGrid) (Object) this).getCpus()) {
            IAEGenericStack output = ((ICraftingCPUCluster) cpu).web$getFinalOutput();
            if (output != null) {
                IAEKey key = output.web$what();
                if (key != null && (filter == null || filter.apply(key))) {
                    result.add(key);
                }
            }
        }
        return result;
    }
}
