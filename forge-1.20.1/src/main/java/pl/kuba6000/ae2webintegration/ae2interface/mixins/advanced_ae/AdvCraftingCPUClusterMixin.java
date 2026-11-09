package pl.kuba6000.ae2webintegration.ae2interface.mixins.advanced_ae;

import java.util.HashMap;
import java.util.UUID;

import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.config.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IAdvCraftingCPUClusterOwnership;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;

@Mixin(value = AdvCraftingCPUCluster.class, remap = false)
public class AdvCraftingCPUClusterMixin implements IAdvCraftingCPUClusterOwnership {

    @Shadow
    @Final
    private HashMap<UUID, AdvCraftingCPU> activeCpus;

    @Shadow
    private @Nullable AdvCraftingCPU remainingStorageCpu;

    @Shadow
    private long remainingStorage;

    // The native config manager reports selection changes through the general persistence callback.
    @Unique
    private CpuSelectionMode web$selectionMode = CpuSelectionMode.ANY;

    @Override
    public boolean web$ownsCpu(@Nullable UUID id, @NotNull AdvCraftingCPU cpu) {
        // Native getRemainingCapacityCPU replaces the anonymous CPU lazily when its capacity changes.
        return id == null ? remainingStorageCpu == cpu && cpu.getAvailableStorage() == remainingStorage
            : activeCpus.get(id) == cpu;
    }

    @WrapMethod(method = "markDirty")
    private void web$metadataChanged(Operation<Void> original) {
        original.call();
        CpuSelectionMode selectionMode = ((AdvCraftingCPUCluster) (Object) this).getSelectionMode();
        if (web$selectionMode == selectionMode) return;
        web$selectionMode = selectionMode;
        IAEMixinCallbacks callbacks = IAEMixinCallbacks.getInstance();
        for (AdvCraftingCPU cpu : activeCpus.values()) callbacks.cpuMetadataChanged((ICraftingCPUCluster) (Object) cpu);
        if (remainingStorageCpu != null)
            callbacks.cpuMetadataChanged((ICraftingCPUCluster) (Object) remainingStorageCpu);
    }
}
