package pl.kuba6000.ae2webintegration.core.api;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.tracking.AEMixinCallbacks;

public interface IAEMixinCallbacks {

    static IAEMixinCallbacks getInstance() {
        return AEMixinCallbacks.INSTANCE;
    }

    void jobStarted(ICraftingCPUCluster cpuCluster, IAEGrid grid, boolean isMerging, boolean isAuthorPlayer);

    void craftingStatusPostedUpdate(ICraftingCPUCluster cpu, Object diff);

    /** CPU inventory notification; refreshes viewed resources without changing crafting measurements. */
    void cpuInventoryChanged(@NotNull ICraftingCPUCluster cpu, @NotNull IAEKey resource);

    void pushedPattern(ICraftingCPUCluster cpu, IPatternProviderViewable provider, IAECraftingPatternDetails details);

    void jobCompleted(IAEGrid grid, ICraftingCPUCluster cpu);

    void jobCancelled(IAEGrid grid, ICraftingCPUCluster cpu);

}
