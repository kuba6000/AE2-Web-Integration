package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.minecraft.util.IChatComponent;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

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
    boolean init(Map<String, String> getParams) {
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
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        Future<IAECraftingJob> job = gridData.jobs.get(jobID);
        if (job == null) {
            deny("INVALID_ID");
            return;
        }
        if (type == ERequestType.CHECK) {
            JSON_JobData jobData = new JSON_JobData();
            if (jobData.isDone = job.isDone()) {
                try {
                    IAECraftingJob craftingJob = job.get();
                    IAEStorageGrid storageGrid = grid.web$getStorageGrid();
                    IAEMeInventoryItem items = storageGrid.web$getItemInventory();
                    jobData.isSimulating = craftingJob.web$isSimulation();
                    jobData.bytesTotal = craftingJob.web$getByteTotal();
                    IItemList plan;
                    craftingJob.web$populatePlan(plan = AE2Controller.AE2Interface.web$createItemList());
                    jobData.plan = new ArrayList<>();
                    for (IStack stack : plan) {
                        JSON_JobData.JobItem jobItem = new JSON_JobData.JobItem();
                        jobItem.itemid = stack.web$getItemID();
                        jobItem.itemname = stack.web$getDisplayName();
                        jobItem.requested = stack.web$getCountRequestable();
                        jobItem.steps = stack.web$getCountRequestableCrafts();
                        if (jobData.isSimulating) {
                            IStack toExtract = stack.web$copy();
                            toExtract.web$reset();
                            toExtract.web$setStackSize(stack.web$getStackSize());
                            IStack missing = toExtract.web$copy();
                            toExtract = items.web$extractItems(toExtract, AEActionable.SIMULATE, grid);
                            if (toExtract == null) {
                                toExtract = missing.web$copy();
                                toExtract.web$setStackSize(0);
                            }
                            jobItem.stored = toExtract.web$getStackSize();
                            jobItem.missing = missing.web$getStackSize() - toExtract.web$getStackSize();
                        } else {
                            jobItem.stored = stack.web$getStackSize();
                            jobItem.missing = 0;
                        }
                        if (jobItem.missing == 0 && jobItem.requested == 0 && jobItem.stored > 0) {
                            IStack realStack = items.web$getAvailableItem(stack);
                            long available = 0L;
                            if (realStack != null) available = realStack.web$getStackSize();
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
            gridData.jobs.remove(this.jobID);
            done();
        } else if (type == ERequestType.SUBMIT) {
            IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
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
                    IChatComponent error = craftingGrid.web$submitJob(craftingJob, target, true, grid);
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
