package pl.kuba6000.ae2webintegration.core.tracking;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.cpu.CpuLiveCoordinator;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEngine;

public class AEMixinCallbacks implements IAEMixinCallbacks {

    public static AEMixinCallbacks INSTANCE = new AEMixinCallbacks();

    @Override
    public void jobStarted(ICraftingCPUCluster cpuCluster, IAEGrid grid, boolean isMerging, boolean isMachineRequest,
        @Nullable PlayerIdentity nativeRequester) {
        jobChanged(cpuCluster);
        CraftingRequester requester = CraftingRequesterContext.resolve(nativeRequester);
        // The CPU's machine source selects its reserved mode; the standing order still owns the tracked job.
        if (!isMerging && isMachineRequest && requester.source() != CraftingRequester.Source.STANDING_ORDER) {
            StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
            GridPersistentData data = key == null ? null : CoreEngine.GRID_IDENTITIES.getPersistentData(key);
            if (data == null || !data.getSettings()
                .isTrackMachineCrafting()) return;
        }
        AE2JobTracker.addJob(cpuCluster, grid, isMerging, requester);
    }

    @Override
    public void craftingStatusPostedUpdate(ICraftingCPUCluster cpu, @NotNull IAEKey diff) {
        AE2JobTracker.updateCraftingStatus(cpu, diff);
        cpuInventoryChanged(cpu, diff);
    }

    @Override
    public void cpuInventoryChanged(@NotNull ICraftingCPUCluster cpu, @NotNull IAEKey resource) {
        CpuLiveCoordinator live = CoreEngine.getCpuLive();
        if (live != null) live.dirty(cpu, resource);
    }

    @Override
    public void cpuMetadataChanged(@NotNull ICraftingCPUCluster cpu) {
        CpuLiveCoordinator live = CoreEngine.getCpuLive();
        if (live != null) live.metadataChanged(cpu);
    }

    @Override
    public void cpuStateReplaced(@NotNull ICraftingCPUCluster cpu) {
        jobChanged(cpu);
    }

    @Override
    public void pushedPattern(ICraftingCPUCluster cpu, IPatternProviderViewable provider,
        IAECraftingPatternDetails details) {
        CpuLiveCoordinator live = CoreEngine.getCpuLive();
        if (live != null) live.pushedPattern(cpu, details);
        AE2JobTracker.pushedPattern(cpu, provider, details);
    }

    @Override
    public void jobCompleted(IAEGrid grid, ICraftingCPUCluster cpu) {
        jobEnded(grid, cpu);
        AE2JobTracker.completeCrafting(grid, cpu);
    }

    @Override
    public void jobCancelled(IAEGrid grid, ICraftingCPUCluster cpu) {
        jobEnded(grid, cpu);
        AE2JobTracker.cancelCrafting(grid, cpu);
    }

    private static void jobEnded(IAEGrid grid, ICraftingCPUCluster cpu) {
        jobChanged(cpu);
        StandingOrderEngine orders = CoreEngine.getStandingOrders();
        if (orders != null) orders.jobEnded(grid, cpu);
    }

    /** Ends plain HTTP job ownership and the live view independently of whether the job was tracked. */
    private static void jobChanged(ICraftingCPUCluster cpu) {
        AE2Controller.itemIdentities.release(cpu);
        CpuLiveCoordinator live = CoreEngine.getCpuLive();
        if (live != null) live.jobChanged(cpu);
    }
}
