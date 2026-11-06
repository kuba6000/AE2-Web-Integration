package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPUNameIndex;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IExecutingCraftingJobAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IPausableCraftingCPU;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

@Mixin(value = CraftingCPUCluster.class, remap = false)
@SuppressWarnings("UnstableApiUsage")
public class AECraftingCPUClusterMixin implements IPausableCraftingCPU, ICraftingCPUNameIndex {

    @Unique
    private @Nullable StableKey web$stableKey;

    // The native config manager reports selection changes through the general persistence callback.
    @Unique
    private appeng.api.config.CpuSelectionMode web$selectionMode = appeng.api.config.CpuSelectionMode.ANY;

    @WrapMethod(method = "markDirty")
    private void web$metadataChanged(Operation<Void> original) {
        original.call();
        appeng.api.config.CpuSelectionMode selectionMode = ((CraftingCPUCluster) (Object) this).getSelectionMode();
        if (web$selectionMode != selectionMode) {
            web$selectionMode = selectionMode;
            IAEMixinCallbacks.getInstance()
                .cpuMetadataChanged(this);
        }
    }

    @Override
    public boolean web$isValidForGrid(@NotNull IAEGrid grid) {
        CraftingCPUCluster cpu = (CraftingCPUCluster) (Object) this;
        return !cpu.isDestroyed() && cpu.isActive() && cpu.getGrid() == (Object) grid;
    }

    @Override
    public @NotNull StableKey web$getKey() {
        if (web$stableKey == null) {
            CraftingCPUCluster cluster = (CraftingCPUCluster) (Object) this;
            BlockPos position = cluster.getBoundsMin();
            web$stableKey = StableKey.create(sink -> {
                StableKey.writeText(
                    sink,
                    cluster.getLevel()
                        .dimension()
                        .location()
                        .toString());
                sink.putInt(position.getX())
                    .putInt(position.getY())
                    .putInt(position.getZ());
            });
        }
        return web$stableKey;
    }

    @Override
    public void web$setInternalID(int id) {
        AE.cpuInternalIDMap.put(this, id);
    }

    @Override
    public String web$getName() {
        Component name = ((ICraftingCPU) this).getName();
        return name != null ? name.getString() : ("CPU #" + AE.cpuInternalIDMap.getOrDefault(this, -1));
    }

    @Override
    public @Nullable String web$getRequesterName() {
        CraftingCPUCluster cpu = (CraftingCPUCluster) (Object) this;
        IExecutingCraftingJobAccessor job = ((ICraftingCPULogicAccessor) cpu.craftingLogic).web$getJob();
        return job == null ? null
            : job.web$getRequesterName(
                cpu.getLevel()
                    .getServer());
    }

    @Override
    public long web$getAvailableStorage() {
        return ((ICraftingCPU) this).getAvailableStorage();
    }

    @Override
    public long web$getUsedStorage() {
        return -1L;
    }

    @Override
    public long web$getCoProcessors() {
        return ((ICraftingCPU) this).getCoProcessors();
    }

    @Override
    public @NotNull CpuSelectionMode web$getSelectionMode() {
        return switch (((ICraftingCPU) this).getSelectionMode()) {
            case PLAYER_ONLY -> CpuSelectionMode.PLAYER_ONLY;
            case MACHINE_ONLY -> CpuSelectionMode.AUTOMATION_ONLY;
            case ANY -> CpuSelectionMode.ALL;
        };
    }

    @Override
    public boolean web$acceptsPlayerJobs() {
        CraftingCPUCluster cpu = (CraftingCPUCluster) (Object) this;
        return cpu.isActive() && !cpu.isBusy();
    }

    @Override
    public boolean web$isBusy() {
        return ((ICraftingCPU) this).isBusy();
    }

    @Override
    public boolean web$isPaused() {
        return ((CraftingCPUCluster) (Object) this).craftingLogic.isJobSuspended();
    }

    @Override
    public void web$setPaused(boolean paused) {
        ((CraftingCPUCluster) (Object) this).craftingLogic.setJobSuspended(paused);
    }

    @Override
    public void web$cancel() {
        ((ICraftingCPU) this).cancelJob();
    }

    @Override
    public IAEGenericStack web$getFinalOutput() {
        if (web$isBusy()) return (IAEGenericStack) (Object) ((ICraftingCPU) this).getJobStatus()
            .crafting();
        return null;
    }

    @Override
    public void web$getItemLists(@NotNull IStackList active, @NotNull IStackList pending, @NotNull IStackList stored) {
        KeyCounter activeItems = (KeyCounter) (Object) active;
        KeyCounter pendingItems = (KeyCounter) (Object) pending;
        KeyCounter storedItems = (KeyCounter) (Object) stored;
        CraftingCpuLogic logic = ((CraftingCPUCluster) (Object) this).craftingLogic;
        IExecutingCraftingJobAccessor job = ((ICraftingCPULogicAccessor) logic).web$getJob();
        if (job != null) activeItems.addAll(job.web$getWaitingFor().list);
        storedItems.addAll(logic.getInventory().list);
        logic.getAllItems(pendingItems);
        // Native getAllItems is active + pending + stored; subtract the copied counters to isolate pending.
        pendingItems.removeAll(activeItems);
        pendingItems.removeAll(storedItems);
        activeItems.removeZeros();
        pendingItems.removeZeros();
        storedItems.removeZeros();
    }

    @Override
    public IStackList web$getWaitingFor() {
        return (IStackList) (Object) ((ICraftingCPULogicAccessor) ((CraftingCPUCluster) (Object) this).craftingLogic)
            .web$getJob()
            .web$getWaitingFor().list;
    }
}
