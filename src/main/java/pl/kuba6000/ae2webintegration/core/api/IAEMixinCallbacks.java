package pl.kuba6000.ae2webintegration.core.api;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AEMixinCallbacks;

public interface IAEMixinCallbacks {

    static IAEMixinCallbacks getInstance() {
        return AEMixinCallbacks.INSTANCE;
    }

    default void jobStarted(ICraftingCPUCluster cpuCluster, IAECraftingGrid cache, IAEGrid grid, boolean isMerging,
        boolean isAuthorPlayer) {
        jobStarted(cpuCluster, cache, grid, isMerging, isAuthorPlayer, null);
    }

    void jobStarted(ICraftingCPUCluster cpuCluster, IAECraftingGrid cache, IAEGrid grid, boolean isMerging,
        boolean isAuthorPlayer, String requester);

    void craftingStatusPostedUpdate(ICraftingCPUCluster cpu, Object diff);

    void pushedPattern(ICraftingCPUCluster cpu, IPatternProviderViewable provider, IAECraftingPatternDetails details);

    void jobCompleted(IAEGrid grid, ICraftingCPUCluster cpu);

    void jobCancelled(IAEGrid grid, ICraftingCPUCluster cpu);

}
