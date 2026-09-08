package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;

public class CancelCPU extends ISyncedRequest {

    // Assigned by successful init() before the request is submitted.
    @SuppressWarnings("NotNullFieldNotInitialized")
    private @NotNull StableKey cpuId;

    @Override
    boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("cpu")) {
            noParam("cpu");
            return false;
        }
        try {
            cpuId = StableKey.parse(getParams.get("cpu"));
        } catch (IllegalArgumentException e) {
            deny("CPU_NOT_FOUND");
            return false;
        }
        return true;
    }

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        Map<StableKey, ICraftingCPUCluster> cpus = GetCPUList.getCPUList(grid.web$getCraftingGrid());
        ICraftingCPUCluster cluster = cpus.get(cpuId);
        if (cluster == null) {
            deny("CPU_NOT_FOUND");
            return;
        }
        if (cluster.web$isBusy()) {
            cluster.web$cancel();
            done();
            return;
        }
        deny("CPU_NOT_BUSY");
    }
}
