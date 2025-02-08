package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.Map;

import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;

public class CancelCPU extends ISyncedRequest {

    private String cpuName;

    @Override
    public boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("cpu")) {
            noParam("cpu");
            return false;
        }
        cpuName = getParams.get("cpu");
        return true;
    }

    @Override
    public void handle(IAEGrid grid) {
        ICraftingCPUCluster cluster = GetCPUList.getCPUList(grid.getCraftingGrid())
            .get(cpuName);
        if (cluster == null) {
            deny("CPU_NOT_FOUND");
            return;
        }
        if (cluster.isBusy()) {
            cluster.cancel();
            done();
            return;
        }
        deny("CPU_NOT_BUSY");
    }
}
