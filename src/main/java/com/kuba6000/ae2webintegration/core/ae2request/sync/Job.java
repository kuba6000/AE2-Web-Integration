package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.minecraft.util.IChatComponent;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public class Job extends ISyncedRequest {

    private static class JSON_JobData {

        boolean isDone;
        public boolean isSimulating;
        public long bytesTotal;
        public ArrayList<JobItem> plan;

        public static class JobItem {

            public String itemid;
            public String itemname;
            public long stored;
            public long requested;
            public long missing;
            public long steps;
            public double usedPercent;
        }
    }

    private enum ERequestType {
        CHECK,
        CANCEL,
        SUBMIT
    }

    private ERequestType type = null;
    private int jobID;
    private String cpuName;

    @Override
    public boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("id")) {
            noParam("id");
            return false;
        }
        this.jobID = Integer.parseInt(getParams.get("id"));
        if (getParams.containsKey("cancel")) this.type = ERequestType.CANCEL;
        else if (getParams.containsKey("submit")) {
            this.type = ERequestType.SUBMIT;
            if (getParams.containsKey("cpu")) this.cpuName = getParams.get("cpu");
        } else this.type = ERequestType.CHECK;
        return true;
    }

    @Override
    public void handle(IAEGrid grid) {
        Future<IAECraftingJob> job = AE2Controller.jobs.get(jobID);
        if (job == null) {
            deny("INVALID_ID");
            return;
        }
        if (type == ERequestType.CHECK) {
            JSON_JobData jobData = new JSON_JobData();
            if (jobData.isDone = job.isDone()) {
                try {
                    IAECraftingJob craftingJob = job.get();
                    IAEStorageGrid storageGrid = grid.getStorageGrid();
                    IAEMeInventoryItem items = storageGrid.getItemInventory();
                    jobData.isSimulating = craftingJob.isSimulation();
                    jobData.bytesTotal = craftingJob.getByteTotal();
                    IItemList plan;
                    craftingJob.populatePlan(plan = AE2Controller.AE2Interface.createItemList());
                    jobData.plan = new ArrayList<>();
                    for (IItemStack stack : plan) {
                        JSON_JobData.JobItem jobItem = new JSON_JobData.JobItem();
                        jobItem.itemid = stack.getItemID();
                        jobItem.itemname = stack.getDisplayName();
                        jobItem.requested = stack.getCountRequestable();
                        jobItem.steps = stack.getCountRequestableCrafts();
                        if (jobData.isSimulating) {
                            IItemStack toExtract = stack.copy();
                            toExtract.reset();
                            toExtract.setStackSize(stack.getStackSize());
                            IItemStack missing = toExtract.copy();
                            toExtract = items.extractItems(toExtract, AEActionable.SIMULATE, grid);
                            if (toExtract == null) {
                                toExtract = missing.copy();
                                toExtract.setStackSize(0);
                            }
                            jobItem.stored = toExtract.getStackSize();
                            jobItem.missing = missing.getStackSize() - toExtract.getStackSize();
                        } else {
                            jobItem.stored = stack.getStackSize();
                            jobItem.missing = 0;
                        }
                        if (jobItem.missing == 0 && jobItem.requested == 0 && jobItem.stored > 0) {
                            IItemStack realStack = items.getAvailableItem(stack);
                            long available = 0L;
                            if (realStack != null) available = realStack.getStackSize();
                            if (available > 0L) jobItem.usedPercent = (double) jobItem.stored / (double) available;
                        }
                        jobData.plan.add(jobItem);
                    }
                    // TODO Move sorting to javascript!
                    jobData.plan.sort((i1, i2) -> {
                        if (i1.missing > 0 && i2.missing > 0) return Long.compare(i2.missing, i1.missing);
                        else if (i1.missing > 0 && i2.missing == 0) return -1;
                        else if (i1.missing == 0 && i2.missing > 0) return 1;
                        if (i1.requested > 0 && i2.requested > 0) return Long.compare(i2.steps, i1.steps);
                        else if (i1.requested > 0 && i2.requested == 0) return -1;
                        else if (i1.requested == 0 && i2.requested > 0) return 1;
                        return Long.compare(i2.stored, i1.stored);
                    });
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                    deny("INTERNAL_ERROR");
                    return;
                }
            }
            setData(jobData);
            done();
        } else if (type == ERequestType.CANCEL) {
            job.cancel(true);
            AE2Controller.jobs.remove(this.jobID);
            done();
        } else if (type == ERequestType.SUBMIT) {
            IAECraftingGrid craftingGrid = grid.getCraftingGrid();
            if (job.isDone()) {
                try {
                    IAECraftingJob craftingJob = job.get();
                    ICraftingCPUCluster target = null;
                    if (cpuName != null) {
                        target = GetCPUList.getCPUList(craftingGrid)
                            .get(cpuName);
                        if (target == null) {
                            deny("CPU_NOT_FOUND");
                            return;
                        }
                    }
                    IChatComponent error = craftingGrid.submitJob(craftingJob, target, true, grid);
                    if (error != null) {
                        deny("FAIL");
                        setData(error.getUnformattedTextForChat());
                    } else {
                        done();
                    }
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                    deny("INTERNAL_ERROR");
                }
            } else {
                deny("JOB_NOT_DONE");
            }
        }
    }

}
