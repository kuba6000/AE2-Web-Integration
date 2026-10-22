package pl.kuba6000.ae2webintegration.ae2interface.mixins.advanced_ae;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.KeyCounter;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IAdvCraftingCPUClusterOwnership;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPUNameIndex;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IExecutingCraftingJobAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

@Mixin(value = AdvCraftingCPU.class, remap = false)
@SuppressWarnings("UnstableApiUsage")
public class AdvCraftingCPUMixin implements ICraftingCPUCluster, ICraftingCPUNameIndex {

    @Shadow
    @Final
    @Nullable
    private UUID uniqueId;

    @Shadow
    @Final
    private AdvCraftingCPUCluster cluster;

    @Unique
    private @Nullable StableKey web$stableKey;

    @Override
    public boolean web$isValidForGrid(@NotNull IAEGrid grid) {
        return !cluster.isDestroyed() && cluster.getGrid() == (Object) grid
            && ((IAdvCraftingCPUClusterOwnership) cluster).web$ownsCpu(uniqueId, (AdvCraftingCPU) (Object) this);
    }

    @Override
    public @NotNull StableKey web$getKey() {
        if (web$stableKey == null) {
            web$stableKey = StableKey.create(sink -> {
                if (uniqueId != null) {
                    sink.putLong(uniqueId.getMostSignificantBits())
                        .putLong(uniqueId.getLeastSignificantBits());
                } else {
                    // The free-capacity CPU has no UUID and is recreated as available storage changes.
                    BlockPos position = cluster.getBoundsMin();
                    StableKey.writeText(
                        sink,
                        cluster.getLevel()
                            .dimension()
                            .location()
                            .toString());
                    sink.putInt(position.getX())
                        .putInt(position.getY())
                        .putInt(position.getZ());
                }
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
        AdvCraftingCPU cpu = (AdvCraftingCPU) (Object) this;
        return cpu.isActive() && !cpu.isBusy();
    }

    @Override
    public boolean web$isBusy() {
        return ((ICraftingCPU) this).isBusy();
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
        AdvCraftingCPULogic logic = ((AdvCraftingCPU) (Object) this).craftingLogic;
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
        return (IStackList) (Object) ((ICraftingCPULogicAccessor) ((AdvCraftingCPU) (Object) this).craftingLogic)
            .web$getJob()
            .web$getWaitingFor().list;
    }
}
