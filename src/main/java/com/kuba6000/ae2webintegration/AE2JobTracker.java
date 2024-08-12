package com.kuba6000.ae2webintegration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.kuba6000.ae2webintegration.mixins.AE2.CraftingCPUClusterAccessor;
import com.kuba6000.ae2webintegration.mixins.AE2.CraftingLinkAccessor;
import com.kuba6000.ae2webintegration.utils.GSONUtils;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.CraftingLink;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import cpw.mods.fml.common.registry.GameRegistry;

public class AE2JobTracker {

    public static class JobTrackingInfo {

        public IAEItemStack finalOutput;
        public long timeStarted;
        public long timeDone;
        public HashMap<IAEItemStack, Long> timeSpentOn = new HashMap<>();
        public HashMap<IAEItemStack, Long> startedWaitingFor = new HashMap<>();
        public HashMap<IAEItemStack, Long> craftedTotal = new HashMap<>();
        public HashMap<IAEItemStack, Long> waitingFor = new HashMap<>();
        public boolean isDone = false;
        public boolean wasCancelled = false;

        public long getTimeSpentOn(IAEItemStack stack) {
            Long time = timeSpentOn.get(stack);
            if (time == null) return 0L;
            Long additionalTime = startedWaitingFor.get(stack);
            if (additionalTime != null) {
                time += System.nanoTime() - additionalTime;
            }
            return time;
        }
    }

    public static class CompactedJobTrackingInfo {

        public static class CompactedTrackingGSONItem {

            public String itemid;
            public String itemname;
            long timeSpentOn;
            long craftedTotal;
        }

        public AE2Controller.GSONItem finalOutput;
        public long timeStarted;
        public long timeDone;
        public ArrayList<CompactedTrackingGSONItem> items = new ArrayList<>();

        public CompactedJobTrackingInfo(JobTrackingInfo info) {
            this.finalOutput = GSONUtils.convertToGSONItem(info.finalOutput);
            this.timeStarted = info.timeStarted;
            this.timeDone = info.timeDone;
            for (Map.Entry<IAEItemStack, Long> iaeItemStackLongEntry : info.timeSpentOn.entrySet()) {
                IAEItemStack stack = iaeItemStackLongEntry.getKey();
                long spent = iaeItemStackLongEntry.getValue();
                CompactedTrackingGSONItem item = new CompactedTrackingGSONItem();
                item.itemid = GameRegistry.findUniqueIdentifierFor(stack.getItem())
                    .toString() + ":"
                    + stack.getItemDamage();
                item.itemname = stack.getItemStack()
                    .getDisplayName();
                item.timeSpentOn = spent;
                item.craftedTotal = info.craftedTotal.get(stack);
                items.add(item);
            }
        }
    }

    public static HashMap<ICraftingCPU, JobTrackingInfo> trackingInfoMap = new HashMap<>();
    public static ConcurrentHashMap<Integer, JobTrackingInfo> trackingInfos = new ConcurrentHashMap<>();

    private static int nextFreeTrackingInfoID = 1;

    public static void addJob(ICraftingLink link) {
        if (link instanceof CraftingLink craftingLink) {
            ICraftingCPU cpu = ((CraftingLinkAccessor) craftingLink).callGetCpu();
            if (cpu instanceof CraftingCPUCluster cpuCluster) {
                JobTrackingInfo info;
                trackingInfoMap.put(cpu, info = new JobTrackingInfo());
                info.timeStarted = System.currentTimeMillis() / 1000L;
                info.finalOutput = cpu.getFinalOutput()
                    .copy();
                for (IAEItemStack iaeItemStack : ((CraftingCPUClusterAccessor) (Object) cpuCluster).getWaitingFor()) {
                    info.startedWaitingFor.put(iaeItemStack, System.nanoTime());
                    info.timeSpentOn.put(iaeItemStack, 0L);
                    info.craftedTotal.put(iaeItemStack, 0L);
                    info.waitingFor.put(iaeItemStack, iaeItemStack.getStackSize());
                }
            }
        }
    }

    public static void updateCraftingStatus(ICraftingCPU cpu, IAEItemStack diff) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        CraftingCPUCluster cpuCluster = (CraftingCPUCluster) cpu;
        IItemList<IAEItemStack> waitingFor = ((CraftingCPUClusterAccessor) (Object) cpuCluster).getWaitingFor();
        IAEItemStack found = waitingFor.findPrecise(diff);
        if (found != null && found.getStackSize() > 0L) {
            if (!info.startedWaitingFor.containsKey(found)) {
                info.startedWaitingFor.put(found, System.nanoTime());
                info.timeSpentOn.putIfAbsent(found, 0L);
                info.waitingFor.put(found, found.getStackSize());
            } else {
                long i = info.waitingFor.get(found);
                long newi = found.getStackSize();
                if (i > newi) {
                    info.craftedTotal.merge(found, i - newi, Long::sum);
                }
                info.waitingFor.put(found, newi);
            }
        } else {
            if (info.startedWaitingFor.containsKey(diff)) {
                Long started = info.startedWaitingFor.remove(diff);
                Long elapsed = System.nanoTime() - started;
                info.timeSpentOn.merge(diff, elapsed, Long::sum);
                info.craftedTotal.merge(diff, info.waitingFor.remove(diff), Long::sum);
            }
        }
    }

    public static void completeCrafting(ICraftingCPU cpu) {
        JobTrackingInfo info = trackingInfoMap.remove(cpu);
        if (info == null) return;
        for (Map.Entry<IAEItemStack, Long> iaeItemStackLongEntry : info.waitingFor.entrySet()) {
            info.craftedTotal.merge(iaeItemStackLongEntry.getKey(), iaeItemStackLongEntry.getValue(), Long::sum);
        }
        info.waitingFor.clear();
        for (Map.Entry<IAEItemStack, Long> iaeItemStackLongEntry : info.startedWaitingFor.entrySet()) {
            info.timeSpentOn
                .merge(iaeItemStackLongEntry.getKey(), System.nanoTime() - iaeItemStackLongEntry.getValue(), Long::sum);
        }
        info.startedWaitingFor.clear();
        info.isDone = true;
        info.timeDone = System.currentTimeMillis() / 1000L;
        trackingInfos.put(nextFreeTrackingInfoID++, info);
    }

    public static void cancelCrafting(ICraftingCPU cpu) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        completeCrafting(cpu);
        info.wasCancelled = true;
    }

}
