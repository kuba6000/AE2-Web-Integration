package com.kuba6000.ae2webintegration.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.tuple.Pair;

import com.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import com.kuba6000.ae2webintegration.core.discord.DiscordManager;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;
import com.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;

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

        public IAEGenericStack finalOutput;
        public long timeStarted;
        public long timeDone;
        public HashMap<IAEKey, Long> timeSpentOn = new HashMap<>();
        public HashMap<IAEKey, Long> startedWaitingFor = new HashMap<>();
        public HashMap<IAEKey, Long> craftedTotal = new HashMap<>();
        public HashMap<IAEKey, Long> waitingFor = new HashMap<>();
        public HashMap<IAEKey, ArrayList<Pair<Long, Long>>> itemShare = new HashMap<>();
        public HashMap<AEInterface, ArrayList<Pair<Long, Long>>> interfaceShare = new HashMap<>();
        public HashMap<AEInterface, Long> interfaceStarted = new HashMap<>();
        public HashMap<AEInterface, AEInterface> interfaceLookup = new HashMap<>();
        public HashMap<AEInterface, HashSet<IAEKey>> interfaceWaitingFor = new HashMap<>();
        public HashMap<IAEKey, HashMap<AEInterface, HashSet<IAEKey>>> interfaceWaitingForLookup = new HashMap<>();
        public boolean isDone = false;
        public boolean wasCancelled = false;

        public long getTimeSpentOn(IAEKey stack) {
            Long time = timeSpentOn.get(stack);
            if (time == null) return 0L;
            Long additionalTime = startedWaitingFor.get(stack);
            if (additionalTime != null) {
                time += System.currentTimeMillis() - additionalTime;
            }
            return time;
        }

        public double getShareInCraftingTime(IAEKey stack) {
            long total = 0L;
            long stackTime = 0L;
            for (IAEKey itemStack : timeSpentOn.keySet()) {
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

    public static IdentityHashMap<ICraftingCPUCluster, JobTrackingInfo> trackingInfoMap = new IdentityHashMap<>();
    public ConcurrentHashMap<Integer, JobTrackingInfo> trackingInfos = new ConcurrentHashMap<>();

    private int nextFreeTrackingInfoID = 1;

    public static void addJob(ICraftingCPUCluster cpuCluster, IAECraftingGrid cache, IAEGrid grid, boolean isMerging) {
        GridData gridData = GridData.get(grid);
        if (gridData == null || !gridData.isTracked) return; // We don't track this grid, so we don't track jobs on it
        JobTrackingInfo info;
        if (isMerging) {
            info = trackingInfoMap.get(cpuCluster);
            if (info == null) return; // We can't start tracking mid crafting :P
        } else {
            trackingInfoMap.put(cpuCluster, info = new JobTrackingInfo());
            info.timeStarted = System.currentTimeMillis();
        }
        info.finalOutput = cpuCluster.web$getFinalOutput()
            .web$copy();
    }

    public static void updateCraftingStatus(ICraftingCPUCluster cpu, IAEKey diff) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        IItemList waitingFor = cpu.web$getWaitingFor();
        long found = waitingFor.web$findPrecise(diff);
        if (found > 0L) {
            if (!info.startedWaitingFor.containsKey(diff)) {
                info.startedWaitingFor.put(diff, System.currentTimeMillis());
                info.timeSpentOn.putIfAbsent(diff, 0L);
                info.waitingFor.put(diff, found);
            } else {
                long i = info.waitingFor.get(diff);
                if (i > found) {
                    info.craftedTotal.merge(diff, i - found, Long::sum);
                }
                info.waitingFor.put(diff, found);
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
                    for (Map.Entry<AEInterface, HashSet<IAEKey>> entry : info.interfaceWaitingForLookup.get(diff)
                        .entrySet()) {
                        AEInterface aeInterface = entry.getKey();
                        HashSet<IAEKey> itemList = entry.getValue();
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
            String name = provider.web$getName();
            if (name == null) name = "[NULL]";
            final AEInterface aeInterfaceToLookup = new AEInterface(name);
            final AEInterface aeInterface = info.interfaceLookup
                .computeIfAbsent(aeInterfaceToLookup, k -> aeInterfaceToLookup);
            aeInterface.location.add(provider.web$getLocation());
            info.interfaceStarted.computeIfAbsent(aeInterface, k -> System.currentTimeMillis());
            final HashSet<IAEKey> itemList = info.interfaceWaitingFor
                .computeIfAbsent(aeInterface, k -> new HashSet<>());

            for (IAEGenericStack out : details.web$getCondensedOutputs()) {
                info.interfaceWaitingForLookup.computeIfAbsent(out.web$what(), k -> new HashMap<>())
                    .putIfAbsent(aeInterface, itemList);
                itemList.add(out.web$what());
            }
        }
    }

    public static void completeCrafting(IAEGrid grid, ICraftingCPUCluster cpu) {
        JobTrackingInfo info = trackingInfoMap.remove(cpu);
        if (info == null) return;
        GridData gridData = GridData.get(grid);
        if (gridData == null || !gridData.isTracked) return; // We don't track this grid, so we don't track jobs on it
        for (Map.Entry<IAEKey, Long> entry : info.waitingFor.entrySet()) {
            info.craftedTotal.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        info.waitingFor.clear();
        final long now = System.currentTimeMillis();
        for (Map.Entry<IAEKey, Long> entry : info.startedWaitingFor.entrySet()) {
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
        gridData.trackingInfo.trackingInfos.put(gridData.trackingInfo.nextFreeTrackingInfoID++, info);
        double took = info.timeDone - info.timeStarted;
        took /= 1000d;
        if (!Config.INSTANCE.AE_PUBLIC_MODE.get() && !Config.INSTANCE.DISCORD_WEBHOOK.get()
            .isEmpty()) {
            IAESecurityGrid securityGrid = grid.web$getSecurityGrid();
            if (securityGrid != null && securityGrid.web$isAvailable()) {
                DiscordManager.postMessageNonBlocking(
                    new DiscordManager.DiscordEmbed(
                        "AE2 Job Tracker [ Grid " + securityGrid.web$getSecurityKey()
                            + " ][ "
                            + cpu.web$getName()
                            + " ]",
                        "Crafting for `" + info.finalOutput.web$what()
                            .web$getDisplayName()
                            + " x"
                            + info.finalOutput.web$amount()
                            + "` "
                            + (info.wasCancelled ? "cancelled" : "completed")
                            + "!\nIt took "
                            + took
                            + "s",
                        info.wasCancelled ? 15548997 : 5763719));
            }
        }
    }

    public static void cancelCrafting(IAEGrid grid, ICraftingCPUCluster cpu) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        info.wasCancelled = true;
        completeCrafting(grid, cpu);
    }

}
