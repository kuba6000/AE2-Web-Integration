package com.kuba6000.ae2webintegration.api;

import java.util.ArrayList;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import com.kuba6000.ae2webintegration.AE2JobTracker;

import appeng.api.storage.data.IAEItemStack;
import cpw.mods.fml.common.registry.GameRegistry;

public class JSON_CompactedJobTrackingInfo {

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

        public ArrayList<AEInterfaceGSON.timingClass> timings = new ArrayList<>();
        public long timingsCombined;
    }

    public ArrayList<AEInterfaceGSON> interfaceShare = new ArrayList<>();

    public JSON_CompactedJobTrackingInfo(AE2JobTracker.JobTrackingInfo info) {
        this.finalOutput = JSON_Item.create(info.finalOutput);
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
            item.shareInCraftingTimeCombined = Math.min(((double) item.timeSpentOn) / (double) elapsed, 1d);
            item.craftsPerSec = (double) item.craftedTotal / (item.timeSpentOn / 1000d);
            items.add(item);
        }
        items.sort((i1, i2) -> Double.compare(i2.shareInCraftingTime, i1.shareInCraftingTime));
        for (Map.Entry<AE2JobTracker.AEInterface, ArrayList<Pair<Long, Long>>> entry : info.interfaceShare.entrySet()) {
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
