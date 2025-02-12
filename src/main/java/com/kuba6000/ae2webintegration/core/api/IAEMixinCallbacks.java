package com.kuba6000.ae2webintegration.core.api;

import com.kuba6000.ae2webintegration.core.AEMixinCallbacks;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

public interface IAEMixinCallbacks {

    static IAEMixinCallbacks getInstance() {
        return AEMixinCallbacks.INSTANCE;
    }

    void jobStarted(ICraftingCPUCluster cpuCluster, IAECraftingGrid cache, IAEGrid grid, boolean isMerging,
        boolean isAuthorPlayer);

    void craftingStatusPostedUpdate(ICraftingCPUCluster cpu, IItemStack diff);

    void pushedPattern(ICraftingCPUCluster cpu, IPatternProviderViewable provider, IAECraftingPatternDetails details);

    void jobCompleted(ICraftingCPUCluster cpu);

    void jobCancelled(ICraftingCPUCluster cpu);

}
