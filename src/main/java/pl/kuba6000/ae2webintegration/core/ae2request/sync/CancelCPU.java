package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.Map;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;

public class CancelCPU extends ISyncedRequest {

    private String cpuName;

    @Override
    boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("cpu")) {
            noParam("cpu");
            return false;
        }
        cpuName = getParams.get("cpu");
        return true;
    }

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        ICraftingCPUCluster cluster = GetCPUList.getCPUList(grid.web$getCraftingGrid())
            .get(cpuName);
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
