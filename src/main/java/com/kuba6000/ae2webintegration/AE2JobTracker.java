package com.kuba6000.ae2webintegration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.tuple.Pair;

import com.kuba6000.ae2webintegration.discord.DiscordManager;
import com.kuba6000.ae2webintegration.mixins.AE2.CraftingCPUClusterAccessor;
import com.kuba6000.ae2webintegration.mixins.AE2.CraftingLinkAccessor;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.CraftingLink;
import appeng.helpers.IInterfaceHost;
import appeng.me.Grid;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;

public class AE2JobTracker {

    public static class AEInterface {

        public String name;

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

    // TODO find something better?
    private static final IdentityHashMap<ICraftingMedium, IInterfaceHost> mediumToViewable = new IdentityHashMap<>();
    private static boolean isUpdatingPatterns = false;
    private static ICraftingProvider currentCraftingProvider = null;

    public static void updatingPatterns(CraftingGridCache craftingGrid, IGrid grid) {
        if (!AE2Controller.tryValidateOrVerify((Grid) grid, craftingGrid)) return;
        mediumToViewable.clear();
        isUpdatingPatterns = true;
    }

    public static void provideCrafting(CraftingGridCache craftingGrid, IGrid grid, ICraftingProvider provider) {
        if (!isUpdatingPatterns) return;
        currentCraftingProvider = provider;
    }

    public static void addCraftingOption(CraftingGridCache craftingGrid, IGrid grid, ICraftingMedium medium) {
        if (!isUpdatingPatterns) return;
        if (currentCraftingProvider == null) return;
        if (currentCraftingProvider instanceof IInterfaceHost viewable && !mediumToViewable.containsKey(medium))
            mediumToViewable.put(medium, viewable);
    }

    public static void doneUpdatingPatterns(CraftingGridCache craftingGrid, IGrid grid) {
        isUpdatingPatterns = false;
    }

    public static class JobTrackingInfo {

        public IAEItemStack finalOutput;
        public long timeStarted;
        public long timeDone;
        public HashMap<IAEItemStack, Long> timeSpentOn = new HashMap<>();
        public HashMap<IAEItemStack, Long> startedWaitingFor = new HashMap<>();
        public HashMap<IAEItemStack, Long> craftedTotal = new HashMap<>();
        public HashMap<IAEItemStack, Long> waitingFor = new HashMap<>();
        public HashMap<AEInterface, ArrayList<Pair<Long, Long>>> interfaceShare = new HashMap<>();
        public HashMap<AEInterface, Long> interfaceStarted = new HashMap<>();
        public HashMap<AEInterface, AEInterface> interfaceLookup = new HashMap<>();
        public HashMap<AEInterface, HashSet<IAEItemStack>> interfaceWaitingFor = new HashMap<>();
        public HashMap<IAEItemStack, HashMap<AEInterface, HashSet<IAEItemStack>>> interfaceWaitingForLookup = new HashMap<>();
        public boolean isDone = false;
        public boolean wasCancelled = false;

        public long getTimeSpentOn(IAEItemStack stack) {
            Long time = timeSpentOn.get(stack);
            if (time == null) return 0L;
            Long additionalTime = startedWaitingFor.get(stack);
            if (additionalTime != null) {
                time += System.currentTimeMillis() - additionalTime;
            }
            return time;
        }

        public double getShareInCraftingTime(IAEItemStack stack) {
            long total = 0L;
            long stackTime = 0L;
            for (IAEItemStack iaeItemStack : timeSpentOn.keySet()) {
                long timeSpent = getTimeSpentOn(iaeItemStack);
                total += timeSpent;
                if (stack.isSameType(iaeItemStack)) {
                    stackTime = timeSpent;
                }
            }
            if (total == 0L) return 1d;
            return (double) stackTime / (double) total;
        }
    }

    public static HashMap<ICraftingCPU, JobTrackingInfo> trackingInfoMap = new HashMap<>();
    public static ConcurrentHashMap<Integer, JobTrackingInfo> trackingInfos = new ConcurrentHashMap<>();

    private static int nextFreeTrackingInfoID = 1;

    public static void addJob(ICraftingLink link, CraftingGridCache cache, IGrid grid) {
        if (!AE2Controller.tryValidateOrVerify((Grid) grid, cache)) return;
        if (link instanceof CraftingLink craftingLink) {
            ICraftingCPU cpu = ((CraftingLinkAccessor) craftingLink).callGetCpu();
            if (cpu instanceof CraftingCPUCluster cpuCluster) {
                JobTrackingInfo info;
                trackingInfoMap.put(cpuCluster, info = new JobTrackingInfo());
                info.timeStarted = System.currentTimeMillis();
                info.finalOutput = cpuCluster.getFinalOutput()
                    .copy();
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
                info.startedWaitingFor.put(found, System.currentTimeMillis());
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
                long started = info.startedWaitingFor.remove(diff);
                long ended = System.currentTimeMillis();
                long elapsed = ended - started;
                long endedReal = System.currentTimeMillis();
                info.timeSpentOn.merge(diff, elapsed, Long::sum);
                info.craftedTotal.merge(diff, info.waitingFor.remove(diff), Long::sum);
                if (info.interfaceWaitingForLookup.containsKey(diff)) {
                    for (Map.Entry<AEInterface, HashSet<IAEItemStack>> entry : info.interfaceWaitingForLookup.get(diff)
                        .entrySet()) {
                        AEInterface aeInterface = entry.getKey();
                        HashSet<IAEItemStack> itemList = entry.getValue();
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

    public static void pushedPattern(CraftingCPUCluster cpu, ICraftingMedium medium, ICraftingPatternDetails details) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        IInterfaceHost viewable = mediumToViewable.get(medium);
        if (viewable != null) {
            String name = viewable.getInterfaceDuality()
                .getTermName();
            if (name == null) name = "[NULL]";
            final AEInterface aeInterfaceToLookup = new AEInterface(name);
            final AEInterface aeInterface = info.interfaceLookup
                .computeIfAbsent(aeInterfaceToLookup, k -> aeInterfaceToLookup);
            info.interfaceStarted.computeIfAbsent(aeInterface, k -> System.currentTimeMillis());
            final HashSet<IAEItemStack> itemList = info.interfaceWaitingFor
                .computeIfAbsent(aeInterface, k -> new HashSet<>());

            for (IAEItemStack out : details.getCondensedOutputs()) {
                info.interfaceWaitingForLookup.computeIfAbsent(out, k -> new HashMap<>())
                    .putIfAbsent(aeInterface, itemList);
                itemList.add(out);
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
        final long now = System.currentTimeMillis();
        for (Map.Entry<IAEItemStack, Long> iaeItemStackLongEntry : info.startedWaitingFor.entrySet()) {
            info.timeSpentOn.merge(iaeItemStackLongEntry.getKey(), now - iaeItemStackLongEntry.getValue(), Long::sum);
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
                "Crafting for `" + info.finalOutput.asItemStackRepresentation()
                    .getDisplayName()
                    + " x"
                    + info.finalOutput.getStackSize()
                    + "` "
                    + (info.wasCancelled ? "cancelled" : "completed")
                    + "!\nIt took "
                    + took
                    + "s",
                info.wasCancelled ? 15548997 : 5763719));
    }

    public static void cancelCrafting(ICraftingCPU cpu) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        info.wasCancelled = true;
        completeCrafting(cpu);
    }

}
