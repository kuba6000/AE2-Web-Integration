package com.kuba6000.ae2webintegration.ae2request.sync;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.minecraft.util.IChatComponent;

import com.kuba6000.ae2webintegration.AE2Controller;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.Grid;
import cpw.mods.fml.common.registry.GameRegistry;

public class Job extends ISyncedRequest {

    static IChatComponent lastFakePlayerChatMessage = null;

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
    public void handle(Grid grid) {
        Future<ICraftingJob> job = AE2Controller.jobs.get(jobID);
        if (job == null) {
            deny("INVALID_ID");
            return;
        }
        if (type == ERequestType.CHECK) {
            JSON_JobData jobData = new JSON_JobData();
            if (jobData.isDone = job.isDone()) {
                try {
                    ICraftingJob craftingJob = job.get();
                    IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
                    IMEInventory<IAEItemStack> items = storageGrid.getItemInventory();
                    final BaseActionSource host = Order.getPlayerSource();
                    jobData.isSimulating = craftingJob.isSimulation();
                    jobData.bytesTotal = craftingJob.getByteTotal();
                    IItemList<IAEItemStack> plan;
                    craftingJob.populatePlan(
                        plan = AEApi.instance()
                            .storage()
                            .createPrimitiveItemList());
                    jobData.plan = new ArrayList<>();
                    for (IAEItemStack iaeItemStack : plan) {
                        JSON_JobData.JobItem jobItem = new JSON_JobData.JobItem();
                        jobItem.itemid = GameRegistry.findUniqueIdentifierFor(iaeItemStack.getItem())
                            .toString() + ":"
                            + iaeItemStack.getItemDamage();
                        jobItem.itemname = iaeItemStack.getItemStack()
                            .getDisplayName();
                        jobItem.requested = iaeItemStack.getCountRequestable();
                        jobItem.steps = iaeItemStack.getCountRequestableCrafts();
                        if (jobData.isSimulating) {
                            IAEItemStack toExtract = iaeItemStack.copy();
                            toExtract.reset();
                            toExtract.setStackSize(iaeItemStack.getStackSize());
                            IAEItemStack missing = toExtract.copy();
                            toExtract = items.extractItems(toExtract, Actionable.SIMULATE, host);
                            if (toExtract == null) {
                                toExtract = missing.copy();
                                toExtract.setStackSize(0);
                            }
                            jobItem.stored = toExtract.getStackSize();
                            jobItem.missing = missing.getStackSize() - toExtract.getStackSize();
                        } else {
                            jobItem.stored = iaeItemStack.getStackSize();
                            jobItem.missing = 0;
                        }
                        if (jobItem.missing == 0 && jobItem.requested == 0 && jobItem.stored > 0) {
                            IAEItemStack realStack = items.getAvailableItem(iaeItemStack);
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
                    setData(jobData);
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                    deny("INTERNAL_ERROR");
                    return;
                }
            }
            done();
        } else if (type == ERequestType.CANCEL) {
            job.cancel(true);
            AE2Controller.jobs.remove(this.jobID);
            done();
        } else if (type == ERequestType.SUBMIT) {
            ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
            if (job.isDone()) {
                try {
                    lastFakePlayerChatMessage = null;
                    ICraftingJob craftingJob = job.get();
                    ICraftingLink linked = craftingGrid
                        .submitJob(craftingJob, null, null, true, Order.getPlayerSource());
                    if (linked == null) {
                        if (lastFakePlayerChatMessage != null) {
                            deny("FAIL");
                            setData(lastFakePlayerChatMessage.getUnformattedTextForChat());
                        } else {
                            deny("FAIL");
                            setData("UNKNOWN");
                        }
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
