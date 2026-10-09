package pl.kuba6000.ae2webintegration.core.http.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.ResourceStack;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

/**
 * A completed or cancelled crafting job with resource and pattern-provider timing measurements.
 *
 * @param finalOutput    detached snapshot of the final crafting output
 * @param timeStarted    crafting start in Unix epoch milliseconds
 * @param timeDone       crafting completion or cancellation in Unix epoch milliseconds
 * @param wasCancelled   whether the crafting work was cancelled
 * @param items          per-resource crafting measurements
 * @param interfaceShare processing measurements grouped by pattern provider name
 * @example timeStarted 1700000000000
 * @example timeDone 1700000010000
 * @example wasCancelled false
 */
@Desugar
public record CraftingHistory(@NotNull ResourceOutput finalOutput, long timeStarted, long timeDone,
    boolean wasCancelled, @NotNull ArrayList<ResourceTiming> items, @NotNull ArrayList<ProviderTiming> interfaceShare) {

    /**
     * One measured processing interval with absolute timestamps.
     *
     * @param started interval start in Unix epoch milliseconds
     * @param ended   interval end in Unix epoch milliseconds
     * @example started 1700000000000
     * @example ended 1700000010000
     */
    @Desugar
    public record Timing(long started, long ended) {}

    /** Completed processing measurements for one resource identity. */
    @SuppressWarnings("unused") // Gson reads the fields reflectively.
    public static final class ResourceTiming extends ResourceView {

        /**
         * Measured processing time for this resource, in milliseconds.
         *
         * @example 10000
         */
        public final long timeSpentOn;
        /**
         * Total resource units produced during the measured work.
         *
         * @example 64
         */
        public final long craftedTotal;
        /**
         * Fraction of summed resource processing time attributed to this resource; one when no processing time is
         * recorded.
         *
         * @example 1.0
         */
        public final double shareInCraftingTime;
        /**
         * Fraction of job elapsed time spent processing this resource, capped at one.
         *
         * @example 1.0
         */
        public final double shareInCraftingTimeCombined;
        /**
         * Produced resource units per second of measured processing time.
         *
         * @example 6.4
         */
        public final double craftsPerSec;
        /** Measured processing intervals. */
        public final @NotNull ArrayList<Timing> timings;
        /**
         * Distinct provider group names used for this resource, matching names in interfaceShare; empty when no
         * provider was recorded.
         */
        public final @NotNull List<String> providers;

        public ResourceTiming(@Nullable String registryNamespace, @Nullable String registryPath,
            @NotNull String displayName, int componentCount, int damage, long timeSpentOn, long craftedTotal,
            double shareInCraftingTime, double shareInCraftingTimeCombined, double craftsPerSec,
            @NotNull ArrayList<Timing> timings, @Nullable IconMappings.Reference icon, @Nullable StableKey itemKey,
            @NotNull List<String> providers) {
            super(registryNamespace, registryPath, displayName, componentCount, damage, itemKey, icon);
            this.timeSpentOn = timeSpentOn;
            this.craftedTotal = craftedTotal;
            this.shareInCraftingTime = shareInCraftingTime;
            this.shareInCraftingTimeCombined = shareInCraftingTimeCombined;
            this.craftsPerSec = craftsPerSec;
            this.timings = timings;
            this.providers = providers;
        }
    }

    /**
     * Processing measurements combined for pattern providers sharing a display name.
     *
     * @param name            pattern provider display name
     * @param timings         measured processing intervals
     * @param timingsCombined sum of provider processing interval durations, in milliseconds
     * @param location        locations of pattern providers sharing this display name
     * @example name Iron Smelter
     * @example timingsCombined 10000
     */
    @Desugar
    public record ProviderTiming(@NotNull String name, @NotNull ArrayList<Timing> timings, long timingsCombined,
        @NotNull HashSet<DimensionalCoords> location) {}

    public static @NotNull CraftingHistory capture(@NotNull AE2JobTracker.JobTrackingInfo info,
        @Nullable IconMappings mappings) {
        long elapsed = info.timeDone - info.timeStarted;
        ArrayList<ResourceTiming> items = new ArrayList<>();
        for (Map.Entry<IAEKey, Long> entry : info.timeSpentOn.entrySet()) {
            IAEKey key = entry.getKey();
            long spent = entry.getValue();
            ResourceStack resource = info.resourceSnapshots.get(key);
            long craftedTotal = info.craftedTotal.get(key);
            double share = info.getShareInCraftingTime(key);
            double combinedShare = elapsed > 0 ? Math.min((double) spent / (double) elapsed, 1d) : 0d;
            double rate = spent > 0 ? (double) craftedTotal / (spent / (double) TimeUnit.SECONDS.toMillis(1)) : 0d;
            ArrayList<Timing> timings = new ArrayList<>();
            for (Pair<Long, Long> interval : info.itemShare.get(key)) {
                timings.add(new Timing(interval.getKey(), interval.getValue()));
            }
            ArrayList<String> providers = new ArrayList<>();
            HashSet<String> recordedProviders = info.resourceProviders.get(key);
            if (recordedProviders != null) providers.addAll(recordedProviders);
            Collections.sort(providers);
            items.add(
                new ResourceTiming(
                    resource.registryNamespace,
                    resource.registryPath,
                    resource.displayName,
                    resource.componentCount,
                    resource.damage,
                    spent,
                    craftedTotal,
                    share,
                    combinedShare,
                    rate,
                    timings,
                    resolveIcon(resource, mappings),
                    resource.itemKey,
                    providers));
        }
        items.sort((first, second) -> Double.compare(second.shareInCraftingTime, first.shareInCraftingTime));
        ArrayList<ProviderTiming> interfaceShare = new ArrayList<>();
        for (Map.Entry<AE2JobTracker.AEInterface, ArrayList<Pair<Long, Long>>> entry : info.interfaceShare.entrySet()) {
            ArrayList<Timing> timings = new ArrayList<>();
            long combined = 0L;
            for (Pair<Long, Long> interval : entry.getValue()) {
                timings.add(new Timing(interval.getKey(), interval.getValue()));
                combined += interval.getValue() - interval.getKey();
            }
            interfaceShare.add(new ProviderTiming(entry.getKey().name, timings, combined, entry.getKey().location));
        }
        interfaceShare.sort((first, second) -> Long.compare(second.timingsCombined(), first.timingsCombined()));
        return new CraftingHistory(
            new ResourceOutput(info.finalOutput, mappings),
            info.timeStarted,
            info.timeDone,
            info.wasCancelled,
            items,
            interfaceShare);
    }

    private static @Nullable IconMappings.Reference resolveIcon(@NotNull ResourceStack resource,
        @Nullable IconMappings mappings) {
        return mappings == null || resource.itemKey == null ? null
            : mappings.resolve(resource.itemKey, resource.iconBaseKey);
    }
}
