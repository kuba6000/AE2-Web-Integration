package pl.kuba6000.ae2webintegration.core.api;

import pl.kuba6000.ae2webintegration.core.AEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

public interface IAEMixinCallbacks {

    static IAEMixinCallbacks getInstance() {
        return AEMixinCallbacks.INSTANCE;
    }

    void jobStarted(ICraftingCPUCluster cpuCluster, IAECraftingGrid cache, IAEGrid grid, boolean isMerging,
        boolean isAuthorPlayer);

    void craftingStatusPostedUpdate(ICraftingCPUCluster cpu, IStack diff);

    void pushedPattern(ICraftingCPUCluster cpu, IPatternProviderViewable provider, IAECraftingPatternDetails details);

    void jobCompleted(IAEGrid grid, ICraftingCPUCluster cpu);

    void jobCancelled(IAEGrid grid, ICraftingCPUCluster cpu);

}
