package com.kuba6000.ae2webintegration.ae2sync;

import java.util.Map;

import appeng.api.networking.crafting.ICraftingGrid;
import appeng.me.Grid;
import appeng.me.cluster.implementations.CraftingCPUCluster;

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
    public void handle(Grid grid) {
        CraftingCPUCluster cluster = GetCPUList.getCPUList(grid.getCache(ICraftingGrid.class))
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
