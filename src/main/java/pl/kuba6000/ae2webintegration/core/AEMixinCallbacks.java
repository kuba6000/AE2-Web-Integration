package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

public class AEMixinCallbacks implements IAEMixinCallbacks {

    public static AEMixinCallbacks INSTANCE = new AEMixinCallbacks();

    @Override
    public void jobStarted(ICraftingCPUCluster cpuCluster, IAECraftingGrid cache, IAEGrid grid, boolean isMerging,
        boolean isAuthorPlayer) {
        if (!Config.TRACKING_TRACK_MACHINE_CRAFTING && !isAuthorPlayer) {
            return;
        }
        AE2JobTracker.addJob(cpuCluster, cache, grid, isMerging);
    }

    @Override
    public void craftingStatusPostedUpdate(ICraftingCPUCluster cpu, IStack diff) {
        AE2JobTracker.updateCraftingStatus(cpu, diff);
    }

    @Override
    public void pushedPattern(ICraftingCPUCluster cpu, IPatternProviderViewable provider,
        IAECraftingPatternDetails details) {
        AE2JobTracker.pushedPattern(cpu, provider, details);
    }

    @Override
    public void jobCompleted(IAEGrid grid, ICraftingCPUCluster cpu) {
        AE2JobTracker.completeCrafting(grid, cpu);
    }

    @Override
    public void jobCancelled(IAEGrid grid, ICraftingCPUCluster cpu) {
        AE2JobTracker.cancelCrafting(grid, cpu);
    }
}
