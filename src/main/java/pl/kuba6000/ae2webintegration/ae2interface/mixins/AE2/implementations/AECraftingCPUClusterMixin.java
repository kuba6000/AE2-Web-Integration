package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPUNameIndex;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

@Mixin(value = CraftingCPUCluster.class, remap = false)
@SuppressWarnings("UnstableApiUsage")
public class AECraftingCPUClusterMixin implements ICraftingCPUCluster, ICraftingCPUNameIndex {

    @Unique
    private @Nullable StableKey web$stableKey;

    @Override
    public @NotNull StableKey web$getKey() {
        if (web$stableKey == null) {
            CraftingCPUCluster cluster = (CraftingCPUCluster) (Object) this;
            var position = cluster.getBoundsMin();
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
    public void web$getAllItems(IStackList list) {
        ((CraftingCPUCluster) (Object) this).craftingLogic.getAllItems((KeyCounter) (Object) list);
    }

    @Override
    public long web$getActiveItems(IAEKey key) {
        return ((CraftingCPUCluster) (Object) this).craftingLogic.getWaitingFor((AEKey) key);
    }

    @Override
    public long web$getPendingItems(IAEKey key) {
        return ((CraftingCPUCluster) (Object) this).craftingLogic.getPendingOutputs((AEKey) key);
    }

    @Override
    public long web$getStorageItems(IAEKey key) {
        return ((CraftingCPUCluster) (Object) this).craftingLogic.getStored((AEKey) key);
    }

    @Override
    public IStackList web$getWaitingFor() {
        return (IStackList) (Object) ((ICraftingCPULogicAccessor) ((CraftingCPUCluster) (Object) this).craftingLogic)
            .web$getJob()
            .web$getWaitingFor().list;
    }
}
