package com.kuba6000.ae2webintegration.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.tuple.Pair;

import com.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import com.kuba6000.ae2webintegration.core.discord.DiscordManager;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

public class AE2JobTracker {

    public static class AEInterface {

        public String name;
        public HashSet<DimensionalCoords> location = new HashSet<>();

        AEInterface(String name) {
            this.name = name;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof AEInterface)) return false;
            return ((AEInterface) obj).name.equals(this.name);
        }
    }

    public static class JobTrackingInfo {

        public IItemStack finalOutput;
        public long timeStarted;
        public long timeDone;
        public HashMap<IItemStack, Long> timeSpentOn = new HashMap<>();
        public HashMap<IItemStack, Long> startedWaitingFor = new HashMap<>();
        public HashMap<IItemStack, Long> craftedTotal = new HashMap<>();
        public HashMap<IItemStack, Long> waitingFor = new HashMap<>();
        public HashMap<IItemStack, ArrayList<Pair<Long, Long>>> itemShare = new HashMap<>();
        public HashMap<AEInterface, ArrayList<Pair<Long, Long>>> interfaceShare = new HashMap<>();
        public HashMap<AEInterface, Long> interfaceStarted = new HashMap<>();
        public HashMap<AEInterface, AEInterface> interfaceLookup = new HashMap<>();
        public HashMap<AEInterface, HashSet<IItemStack>> interfaceWaitingFor = new HashMap<>();
        public HashMap<IItemStack, HashMap<AEInterface, HashSet<IItemStack>>> interfaceWaitingForLookup = new HashMap<>();
        public boolean isDone = false;
        public boolean wasCancelled = false;

        public long getTimeSpentOn(IItemStack stack) {
            Long time = timeSpentOn.get(stack);
            if (time == null) return 0L;
            Long additionalTime = startedWaitingFor.get(stack);
            if (additionalTime != null) {
                time += System.currentTimeMillis() - additionalTime;
            }
            return time;
        }

        public double getShareInCraftingTime(IItemStack stack) {
            long total = 0L;
            long stackTime = 0L;
            for (IItemStack itemStack : timeSpentOn.keySet()) {
                long timeSpent = getTimeSpentOn(itemStack);
                total += timeSpent;
                if (stack.web$isSameType(itemStack)) {
                    stackTime = timeSpent;
                }
            }
            if (total == 0L) return 1d;
            return (double) stackTime / (double) total;
        }
    }

    public static HashMap<ICraftingCPUCluster, JobTrackingInfo> trackingInfoMap = new HashMap<>();
    public static ConcurrentHashMap<Integer, JobTrackingInfo> trackingInfos = new ConcurrentHashMap<>();

    private static int nextFreeTrackingInfoID = 1;

    public static void addJob(ICraftingCPUCluster cpuCluster, IAECraftingGrid cache, IAEGrid grid, boolean isMerging) {
        if (!AE2Controller.tryValidateOrVerify(grid, cache)) return;
        JobTrackingInfo info;
        if (isMerging) {
            info = trackingInfoMap.get(cpuCluster);
            if (info == null) return; // We can't start tracking mid crafting :P
        } else {
            trackingInfoMap.put(cpuCluster.createUnpooledCopy(), info = new JobTrackingInfo());
            info.timeStarted = System.currentTimeMillis();
        }
        info.finalOutput = cpuCluster.getFinalOutput()
            .web$copy();
    }

    public static void updateCraftingStatus(ICraftingCPUCluster cpu, IItemStack diff) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        IItemList waitingFor = cpu.getWaitingFor();
        IItemStack found = waitingFor.findPrecise(diff);
        if (found != null && found.web$getStackSize() > 0L) {
            if (!info.startedWaitingFor.containsKey(found)) {
                info.startedWaitingFor.put(found, System.currentTimeMillis());
                info.timeSpentOn.putIfAbsent(found, 0L);
                info.waitingFor.put(found, found.web$getStackSize());
            } else {
                long i = info.waitingFor.get(found);
                long newi = found.web$getStackSize();
                if (i > newi) {
                    info.craftedTotal.merge(found, i - newi, Long::sum);
                }
                info.waitingFor.put(found, newi);
            }
        } else {
            if (info.startedWaitingFor.containsKey(diff)) {
                long started = info.startedWaitingFor.remove(diff);
                long ended = System.currentTimeMillis();
                long elapsed = ended - started;
                long endedReal = System.currentTimeMillis();
                info.timeSpentOn.merge(diff, elapsed, Long::sum);
                info.craftedTotal.merge(diff, info.waitingFor.remove(diff), Long::sum);
                info.itemShare.computeIfAbsent(diff, k -> new ArrayList<>())
                    .add(Pair.of(started, endedReal));
                if (info.interfaceWaitingForLookup.containsKey(diff)) {
                    for (Map.Entry<AEInterface, HashSet<IItemStack>> entry : info.interfaceWaitingForLookup.get(diff)
                        .entrySet()) {
                        AEInterface aeInterface = entry.getKey();
                        HashSet<IItemStack> itemList = entry.getValue();
                        itemList.remove(diff);
                        if (itemList.isEmpty()) {
                            info.interfaceWaitingFor.remove(aeInterface);
                            long interfaceStarted = info.interfaceStarted.remove(aeInterface);
                            info.interfaceShare.computeIfAbsent(aeInterface, k -> new ArrayList<>())
                                .add(Pair.of(interfaceStarted, endedReal));
                        }
                    }
                    info.interfaceWaitingForLookup.remove(diff);
                }
            }
        }
    }

    public static void pushedPattern(ICraftingCPUCluster cpu, IPatternProviderViewable provider,
        IAECraftingPatternDetails details) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        if (provider != null) {
            String name = provider.getName();
            if (name == null) name = "[NULL]";
            final AEInterface aeInterfaceToLookup = new AEInterface(name);
            final AEInterface aeInterface = info.interfaceLookup
                .computeIfAbsent(aeInterfaceToLookup, k -> aeInterfaceToLookup);
            aeInterface.location.add(provider.getLocation());
            info.interfaceStarted.computeIfAbsent(aeInterface, k -> System.currentTimeMillis());
            final HashSet<IItemStack> itemList = info.interfaceWaitingFor
                .computeIfAbsent(aeInterface, k -> new HashSet<>());

            for (IItemStack out : details.getCondensedOutputs()) {
                info.interfaceWaitingForLookup.computeIfAbsent(out, k -> new HashMap<>())
                    .putIfAbsent(aeInterface, itemList);
                itemList.add(out);
            }
        }
    }

    public static void completeCrafting(ICraftingCPUCluster cpu) {
        JobTrackingInfo info = trackingInfoMap.remove(cpu);
        if (info == null) return;
        for (Map.Entry<IItemStack, Long> entry : info.waitingFor.entrySet()) {
            info.craftedTotal.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        info.waitingFor.clear();
        final long now = System.currentTimeMillis();
        for (Map.Entry<IItemStack, Long> entry : info.startedWaitingFor.entrySet()) {
            info.timeSpentOn.merge(entry.getKey(), now - entry.getValue(), Long::sum);
            info.itemShare.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                .add(Pair.of(entry.getValue(), now));
        }
        for (Map.Entry<AEInterface, Long> entry : info.interfaceStarted.entrySet()) {
            info.interfaceShare.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                .add(Pair.of(entry.getValue(), now));
        }
        info.interfaceStarted.clear();
        info.interfaceWaitingFor.clear();
        info.interfaceWaitingForLookup.clear();
        info.interfaceLookup.clear();
        info.startedWaitingFor.clear();
        info.isDone = true;
        info.timeDone = System.currentTimeMillis();
        trackingInfos.put(nextFreeTrackingInfoID++, info);
        double took = info.timeDone - info.timeStarted;
        took /= 1000d;
        DiscordManager.postMessageNonBlocking(
            new DiscordManager.DiscordEmbed(
                "AE2 Job Tracker",
                "Crafting for `" + info.finalOutput.web$getDisplayName()
                    + " x"
                    + info.finalOutput.web$getStackSize()
                    + "` "
                    + (info.wasCancelled ? "cancelled" : "completed")
                    + "!\nIt took "
                    + took
                    + "s",
                info.wasCancelled ? 15548997 : 5763719));
    }

    public static void cancelCrafting(ICraftingCPUCluster cpu) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        info.wasCancelled = true;
        completeCrafting(cpu);
    }

}
