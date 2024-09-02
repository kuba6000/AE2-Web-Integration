package com.kuba6000.ae2webintegration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.tuple.Pair;

import com.kuba6000.ae2webintegration.api.JSON_Item;
import com.kuba6000.ae2webintegration.mixins.AE2.CraftingCPUClusterAccessor;
import com.kuba6000.ae2webintegration.mixins.AE2.CraftingLinkAccessor;
import com.kuba6000.ae2webintegration.utils.GSONUtils;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.api.util.IInterfaceViewable;
import appeng.crafting.CraftingLink;
import appeng.me.Grid;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import cpw.mods.fml.common.registry.GameRegistry;

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

    private static final IdentityHashMap<ICraftingMedium, IInterfaceViewable> mediumToViewable = new IdentityHashMap<>();
    private static boolean isUpdatingPatterns = false;
    private static ICraftingProvider currentCraftingProvider = null;

    public static void updatingPatterns(CraftingGridCache craftingGrid, IGrid grid) {
        if (!AE2Controller.isValid()) {
            if (craftingGrid.getCpus()
                .size() >= 5) {
                AE2Controller.activeGrid = (Grid) grid;
            } else return;
        } else if (AE2Controller.activeGrid != grid) return;
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
        if (currentCraftingProvider instanceof IInterfaceViewable viewable && !mediumToViewable.containsKey(medium))
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
                time += System.nanoTime() - additionalTime;
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

    public static class CompactedJobTrackingInfo {

        public static class CompactedTrackingGSONItem {

            public String itemid;
            public String itemname;
            public long timeSpentOn;
            public long craftedTotal;
            public double shareInCraftingTime = 0d;
            public double shareInCraftingTimeCombined = 0d;
            public double craftsPerSec = 0d;
        }

        public JSON_Item finalOutput;
        public long timeStarted;
        public long timeDone;
        public boolean wasCancelled;
        public ArrayList<CompactedTrackingGSONItem> items = new ArrayList<>();

        public static class AEInterfaceGSON {

            String name;

            public static class timingClass {

                long started;
                long ended;

                public timingClass(long started, long ended) {
                    this.started = started;
                    this.ended = ended;
                }
            }

            public ArrayList<timingClass> timings = new ArrayList<>();
            public long timingsCombined;
        }

        public ArrayList<AEInterfaceGSON> interfaceShare = new ArrayList<>();

        public CompactedJobTrackingInfo(JobTrackingInfo info) {
            this.finalOutput = GSONUtils.convertToGSONItem(info.finalOutput);
            this.timeStarted = info.timeStarted;
            this.timeDone = info.timeDone;
            long elapsed = this.timeDone - this.timeStarted;
            this.wasCancelled = info.wasCancelled;
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
                item.shareInCraftingTime = info.getShareInCraftingTime(stack);
                item.shareInCraftingTimeCombined = Math
                    .min(((double) (long) (item.timeSpentOn / 1e9d)) / (double) elapsed, 1d);
                item.craftsPerSec = (double) item.craftedTotal / (item.timeSpentOn / 1e9d);
                items.add(item);
            }
            items.sort((i1, i2) -> Double.compare(i2.shareInCraftingTime, i1.shareInCraftingTime));
            for (Map.Entry<AEInterface, ArrayList<Pair<Long, Long>>> entry : info.interfaceShare.entrySet()) {
                AEInterfaceGSON interfaceGSON = new AEInterfaceGSON();
                interfaceGSON.name = entry.getKey().name;
                for (Pair<Long, Long> longLongPair : entry.getValue()) {
                    interfaceGSON.timings
                        .add(new AEInterfaceGSON.timingClass(longLongPair.getKey(), longLongPair.getValue()));
                }
                long interfaceElapsed = 0L;
                for (Pair<Long, Long> pair : entry.getValue()) {
                    interfaceElapsed += pair.getValue() - pair.getKey();
                }
                interfaceGSON.timingsCombined = interfaceElapsed;
                interfaceShare.add(interfaceGSON);
            }
            interfaceShare.sort((i1, i2) -> Long.compare(i2.timingsCombined, i1.timingsCombined));
        }
    }

    public static HashMap<ICraftingCPU, JobTrackingInfo> trackingInfoMap = new HashMap<>();
    public static ConcurrentHashMap<Integer, JobTrackingInfo> trackingInfos = new ConcurrentHashMap<>();

    private static int nextFreeTrackingInfoID = 1;

    public static void addJob(ICraftingLink link, CraftingGridCache cache, IGrid grid) {
        if (link instanceof CraftingLink craftingLink) {
            ICraftingCPU cpu = ((CraftingLinkAccessor) craftingLink).callGetCpu();
            if (cpu instanceof CraftingCPUCluster cpuCluster) {
                if (!AE2Controller.isValid()) {
                    if (cache.getCpus()
                        .size() >= 5) AE2Controller.activeGrid = (Grid) grid;
                    else return;
                } else if (AE2Controller.activeGrid != grid) return;
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
                long started = info.startedWaitingFor.remove(diff);
                long ended = System.nanoTime();
                long elapsed = ended - started;
                long endedReal = System.currentTimeMillis() / 1000L;
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
        IInterfaceViewable viewable = mediumToViewable.get(medium);
        if (viewable != null) {
            String name = viewable.getName();
            if (name == null) name = "[NULL]";
            final AEInterface aeInterfaceToLookup = new AEInterface(name);
            final AEInterface aeInterface = info.interfaceLookup
                .computeIfAbsent(aeInterfaceToLookup, k -> aeInterfaceToLookup);
            info.interfaceStarted.computeIfAbsent(aeInterface, k -> System.currentTimeMillis() / 1000L);
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
        final long now = System.nanoTime();
        final long nowTimeStamp = System.currentTimeMillis() / 1000L;
        for (Map.Entry<IAEItemStack, Long> iaeItemStackLongEntry : info.startedWaitingFor.entrySet()) {
            info.timeSpentOn.merge(iaeItemStackLongEntry.getKey(), now - iaeItemStackLongEntry.getValue(), Long::sum);
        }
        for (Map.Entry<AEInterface, Long> entry : info.interfaceStarted.entrySet()) {
            info.interfaceShare.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                .add(Pair.of(entry.getValue(), nowTimeStamp));
        }
        info.interfaceStarted.clear();
        info.interfaceWaitingFor.clear();
        info.interfaceWaitingForLookup.clear();
        info.interfaceLookup.clear();
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
