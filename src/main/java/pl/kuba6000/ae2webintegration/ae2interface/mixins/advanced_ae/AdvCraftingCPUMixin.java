package pl.kuba6000.ae2webintegration.ae2interface.mixins.advanced_ae;

import java.util.UUID;

import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingCPULogicAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

@Mixin(value = AdvCraftingCPU.class, remap = false)
public class AdvCraftingCPUMixin implements ICraftingCPUCluster {

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
    public @NotNull StableKey web$getId() {
        if (web$stableKey == null) {
            web$stableKey = StableKey.create(sink -> {
                if (uniqueId != null) {
                    StableKey.writeText(sink, "cpu:advanced_ae");
                    sink.putLong(uniqueId.getMostSignificantBits())
                        .putLong(uniqueId.getLeastSignificantBits());
                } else {
                    // The free-capacity CPU has no UUID and is recreated as available storage changes.
                    var position = cluster.getBoundsMin();
                    StableKey.writeText(sink, "cpu:advanced_ae:free");
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
    public boolean web$hasCustomName() {
        return !(((ICraftingCPU) this).getName() == null);
    }

    @Override
    public String web$getName() {
        return web$hasCustomName() ? ((ICraftingCPU) this).getName()
            .getString() : ("CPU #" + AE.cpuInternalIDMap.getOrDefault(this, -1));
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
        ((AdvCraftingCPU) (Object) this).craftingLogic.getAllItems((KeyCounter) (Object) list);
    }

    @Override
    public long web$getActiveItems(IAEKey key) {
        return ((AdvCraftingCPU) (Object) this).craftingLogic.getWaitingFor((AEKey) key);
    }

    @Override
    public long web$getPendingItems(IAEKey key) {
        return ((AdvCraftingCPU) (Object) this).craftingLogic.getWaitingFor((AEKey) key);
    }

    @Override
    public long web$getStorageItems(IAEKey key) {
        return ((AdvCraftingCPU) (Object) this).craftingLogic.getWaitingFor((AEKey) key);
    }

    @Override
    public IStackList web$getWaitingFor() {
        return (IStackList) (Object) ((ICraftingCPULogicAccessor) ((AdvCraftingCPU) (Object) this).craftingLogic)
            .web$getJob()
            .web$getWaitingFor().list;
    }
}
