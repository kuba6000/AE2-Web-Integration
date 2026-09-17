package pl.kuba6000.ae2webintegration.core.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

/** A completed or cancelled crafting job with per-resource and pattern-provider timing measurements. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class JSON_CompactedJobTrackingInfo {

    /** One measured processing interval with absolute timestamps. */
    public static class timingClass {

        /**
         * Interval start in Unix epoch milliseconds.
         *
         * @example 1700000000000
         */
        long started;
        /**
         * Interval end in Unix epoch milliseconds.
         *
         * @example 1700000010000
         */
        long ended;

        public timingClass(long started, long ended) {
            this.started = started;
            this.ended = ended;
        }
    }

    /** Completed processing measurements for one resource identity. */
    public static class CompactedTrackingGSONItem {

        /**
         * Registry resource identifier.
         *
         * @example minecraft:iron_ingot
         */
        public String itemid;
        /**
         * Resource display name.
         *
         * @example Iron Ingot
         */
        public String itemname;
        /**
         * Measured processing time for this resource, in milliseconds.
         *
         * @example 10000
         */
        public long timeSpentOn;
        /**
         * Total resource units produced during the measured work.
         *
         * @example 64
         */
        public long craftedTotal;
        /**
         * Fraction of summed resource processing time attributed to this resource; one when no processing time is
         * recorded.
         *
         * @example 1.0
         */
        public double shareInCraftingTime = 0d;
        /**
         * Fraction of job elapsed time spent processing this resource, capped at one.
         *
         * @example 1.0
         */
        public double shareInCraftingTimeCombined = 0d;
        /**
         * Produced resource units per second of measured processing time.
         *
         * @example 6.4
         */
        public double craftsPerSec = 0d;

        /** Measured processing intervals. */
        public ArrayList<timingClass> timings = new ArrayList<>();
    }

    /** Detached snapshot of the final crafting output. */
    public @NotNull JSON_Stack finalOutput;
    /**
     * Crafting start in Unix epoch milliseconds.
     *
     * @example 1700000000000
     */
    public long timeStarted;
    /**
     * Crafting completion or cancellation in Unix epoch milliseconds.
     *
     * @example 1700000010000
     */
    public long timeDone;
    /**
     * Whether the crafting work was cancelled.
     *
     * @example false
     */
    public boolean wasCancelled;
    /** Per-resource crafting measurements. */
    public ArrayList<CompactedTrackingGSONItem> items = new ArrayList<>();

    /** Processing measurements combined for pattern providers sharing a display name. */
    public static class AEInterfaceGSON {

        /**
         * Pattern provider display name.
         *
         * @example Iron Smelter
         */
        String name;

        /** Measured processing intervals. */
        public ArrayList<timingClass> timings = new ArrayList<>();
        /**
         * Sum of provider processing interval durations, in milliseconds.
         *
         * @example 10000
         */
        public long timingsCombined;

        /** Locations of pattern providers sharing this display name. */
        public HashSet<DimensionalCoords> location = new HashSet<>();
    }

    /** Processing measurements grouped by pattern provider name. */
    public ArrayList<AEInterfaceGSON> interfaceShare = new ArrayList<>();

    public JSON_CompactedJobTrackingInfo(AE2JobTracker.JobTrackingInfo info) {
        this.finalOutput = info.finalOutput;
        this.timeStarted = info.timeStarted;
        this.timeDone = info.timeDone;
        long elapsed = this.timeDone - this.timeStarted;
        this.wasCancelled = info.wasCancelled;
        for (Map.Entry<IAEKey, Long> entry : info.timeSpentOn.entrySet()) {
            IAEKey key = entry.getKey();
            long spent = entry.getValue();
            CompactedTrackingGSONItem item = new CompactedTrackingGSONItem();
            item.itemid = key.web$getItemID();
            item.itemname = key.web$getDisplayName();
            item.timeSpentOn = spent;
            item.craftedTotal = info.craftedTotal.get(key);
            item.shareInCraftingTime = info.getShareInCraftingTime(key);
            item.shareInCraftingTimeCombined = elapsed > 0
                ? Math.min(((double) item.timeSpentOn) / (double) elapsed, 1d)
                : 0d;
            item.craftsPerSec = item.timeSpentOn > 0
                ? (double) item.craftedTotal / (item.timeSpentOn / (double) TimeUnit.SECONDS.toMillis(1))
                : 0d;
            for (Pair<Long, Long> longLongPair : info.itemShare.get(key)) {
                item.timings.add(new timingClass(longLongPair.getKey(), longLongPair.getValue()));
            }
            items.add(item);
        }
        items.sort((i1, i2) -> Double.compare(i2.shareInCraftingTime, i1.shareInCraftingTime));
        for (Map.Entry<AE2JobTracker.AEInterface, ArrayList<Pair<Long, Long>>> entry : info.interfaceShare.entrySet()) {
            AEInterfaceGSON interfaceGSON = new AEInterfaceGSON();
            interfaceGSON.name = entry.getKey().name;
            interfaceGSON.location = entry.getKey().location;
            for (Pair<Long, Long> longLongPair : entry.getValue()) {
                interfaceGSON.timings.add(new timingClass(longLongPair.getKey(), longLongPair.getValue()));
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
