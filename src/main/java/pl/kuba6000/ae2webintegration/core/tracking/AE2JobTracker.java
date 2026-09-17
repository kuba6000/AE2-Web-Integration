package pl.kuba6000.ae2webintegration.core.tracking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.MapMaker;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.JSON_Stack;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.notification.NotificationManager;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;

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

        public volatile @NotNull JSON_Stack finalOutput;
        public long timeStarted;
        public long timeDone;
        public HashMap<IAEKey, Long> timeSpentOn = new HashMap<>();
        public HashMap<IAEKey, Long> startedWaitingFor = new HashMap<>();
        public HashMap<IAEKey, Long> craftedTotal = new HashMap<>();
        public HashMap<IAEKey, Long> waitingFor = new HashMap<>();
        public HashMap<IAEKey, ArrayList<Pair<Long, Long>>> itemShare = new HashMap<>();
        public HashMap<AEInterface, ArrayList<Pair<Long, Long>>> interfaceShare = new HashMap<>();
        public HashMap<AEInterface, Long> interfaceStarted = new HashMap<>();
        public HashMap<String, AEInterface> interfaceLookup = new HashMap<>();
        public HashMap<AEInterface, HashSet<IAEKey>> interfaceWaitingFor = new HashMap<>();
        public HashMap<IAEKey, HashMap<AEInterface, HashSet<IAEKey>>> interfaceWaitingForLookup = new HashMap<>();
        public boolean isDone = false;
        public boolean wasCancelled = false;

        public JobTrackingInfo(@NotNull JSON_Stack finalOutput) {
            this.finalOutput = finalOutput;
            this.timeStarted = System.currentTimeMillis();
        }

        public long getTimeSpentOn(IAEKey key) {
            Long time = timeSpentOn.get(key);
            if (time == null) return 0L;
            Long additionalTime = startedWaitingFor.get(key);
            if (additionalTime != null) {
                time += System.currentTimeMillis() - additionalTime;
            }
            return time;
        }

        public double getShareInCraftingTime(IAEKey key) {
            long total = 0L;
            long stackTime = 0L;
            for (IAEKey itemKey : timeSpentOn.keySet()) {
                long timeSpent = getTimeSpentOn(itemKey);
                total += timeSpent;
                if (key.equals(itemKey)) {
                    stackTime = timeSpent;
                }
            }
            if (total == 0L) return 1d;
            return (double) stackTime / (double) total;
        }
    }

    private static final ConcurrentMap<ICraftingCPUCluster, JobTrackingInfo> trackingInfoMap = new MapMaker().weakKeys()
        .makeMap();
    public ConcurrentHashMap<Integer, JobTrackingInfo> trackingInfos = new ConcurrentHashMap<>();

    private int nextFreeTrackingInfoID = 1;

    public static JobTrackingInfo findActiveJob(ICraftingCPUCluster cpu) {
        return trackingInfoMap.get(cpu);
    }

    public static void clearActiveJobs() {
        trackingInfoMap.clear();
    }

    public void clearHistory() {
        trackingInfos.clear();
        nextFreeTrackingInfoID = 1;
    }

    public static void addJob(ICraftingCPUCluster cpuCluster, IAEGrid grid, boolean isMerging) {
        if (!CoreEngine.GRID_IDENTITIES.isInitialized()) return;
        JobTrackingInfo info = isMerging ? trackingInfoMap.get(cpuCluster) : null;
        if (isMerging && info == null) return;
        if (!isMerging) {
            StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
            GridPersistentData data = key == null ? null : CoreEngine.GRID_IDENTITIES.getPersistentData(key);
            if (data == null || !data.getSettings()
                .isTracked()) return;
        }
        JSON_Stack finalOutput = JSON_Stack.capture(grid, cpuCluster.web$getFinalOutput());
        if (finalOutput == null) {
            trackingInfoMap.remove(cpuCluster);
            return;
        }
        if (isMerging) {
            info.finalOutput = finalOutput;
        } else {
            trackingInfoMap.put(cpuCluster, new JobTrackingInfo(finalOutput));
        }
    }

    public static void updateCraftingStatus(ICraftingCPUCluster cpu, Object diff) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null || !(diff instanceof IAEKey keyDiff)) return;
        IStackList waitingFor = cpu.web$getWaitingFor();
        long waitingAmount = waitingFor.web$getAmount(keyDiff);
        if (waitingAmount > 0L) {
            if (!info.startedWaitingFor.containsKey(keyDiff)) {
                info.startedWaitingFor.put(keyDiff, System.currentTimeMillis());
                info.timeSpentOn.putIfAbsent(keyDiff, 0L);
                info.waitingFor.put(keyDiff, waitingAmount);
            } else {
                long previous = info.waitingFor.get(keyDiff);
                if (previous > waitingAmount) {
                    info.craftedTotal.merge(keyDiff, previous - waitingAmount, Long::sum);
                }
                info.waitingFor.put(keyDiff, waitingAmount);
            }
        } else {
            if (info.startedWaitingFor.containsKey(keyDiff)) {
                long started = info.startedWaitingFor.remove(keyDiff);
                long ended = System.currentTimeMillis();
                long elapsed = ended - started;
                long endedReal = System.currentTimeMillis();
                info.timeSpentOn.merge(keyDiff, elapsed, Long::sum);
                info.craftedTotal.merge(keyDiff, info.waitingFor.remove(keyDiff), Long::sum);
                info.itemShare.computeIfAbsent(keyDiff, k -> new ArrayList<>())
                    .add(Pair.of(started, endedReal));
                if (info.interfaceWaitingForLookup.containsKey(keyDiff)) {
                    for (Map.Entry<AEInterface, HashSet<IAEKey>> entry : info.interfaceWaitingForLookup.get(keyDiff)
                        .entrySet()) {
                        AEInterface aeInterface = entry.getKey();
                        HashSet<IAEKey> itemList = entry.getValue();
                        itemList.remove(keyDiff);
                        if (itemList.isEmpty()) {
                            info.interfaceWaitingFor.remove(aeInterface);
                            long interfaceStarted = info.interfaceStarted.remove(aeInterface);
                            info.interfaceShare.computeIfAbsent(aeInterface, k -> new ArrayList<>())
                                .add(Pair.of(interfaceStarted, endedReal));
                        }
                    }
                    info.interfaceWaitingForLookup.remove(keyDiff);
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
            final AEInterface aeInterface = info.interfaceLookup.computeIfAbsent(name, AEInterface::new);
            aeInterface.location.add(provider.web$getLocation());
            info.interfaceStarted.computeIfAbsent(aeInterface, k -> System.currentTimeMillis());
            final HashSet<IAEKey> itemList = info.interfaceWaitingFor
                .computeIfAbsent(aeInterface, k -> new HashSet<>());

            IAEGenericStack[] condensedOutputs = details.web$getCondensedOutputs();
            for (IAEGenericStack out : condensedOutputs) {
                IAEKey outKey = out.web$what();
                info.interfaceWaitingForLookup.computeIfAbsent(outKey, k -> new HashMap<>())
                    .putIfAbsent(aeInterface, itemList);
                itemList.add(outKey);
            }
        }
    }

    public static void completeCrafting(@Nullable IAEGrid grid, ICraftingCPUCluster cpu) {
        JobTrackingInfo info = trackingInfoMap.remove(cpu);
        if (info == null || grid == null) return;
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        GridPersistentData data = key == null ? null : CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        if (data == null || !data.getSettings()
            .isTracked()) return;
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
        info.timeDone = now;
        GridData gridData = GridData.getOrCreate(key);
        gridData.trackingInfo.trackingInfos.put(gridData.trackingInfo.nextFreeTrackingInfoID++, info);
        long durationMillis = info.timeDone - info.timeStarted;
        long craftedAmount = info.finalOutput.quantity;
        if (!Config.AE_PUBLIC_MODE()
            && NotificationManager.shouldPostCraftingNotification(durationMillis, craftedAmount)) {
            // Native enumeration assigns the fallback CPU display ordinals used by the notification name.
            grid.web$getCraftingGrid()
                .web$getCPUs();
            NotificationManager.postMessageNonBlocking(
                new CraftingMessage(
                    key,
                    cpu.web$getName(),
                    info.finalOutput.itemname,
                    craftedAmount,
                    NotificationManager.formatDuration(durationMillis),
                    info.wasCancelled));
        }
    }

    public static void cancelCrafting(@Nullable IAEGrid grid, ICraftingCPUCluster cpu) {
        JobTrackingInfo info = trackingInfoMap.get(cpu);
        if (info == null) return;
        info.wasCancelled = true;
        completeCrafting(grid, cpu);
    }

}
