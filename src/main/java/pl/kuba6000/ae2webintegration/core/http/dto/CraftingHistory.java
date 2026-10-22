package pl.kuba6000.ae2webintegration.core.http.dto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.crafting.CompletedCrafting;

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
public record CraftingHistory(@NotNull ResourceStack finalOutput, long timeStarted, long timeDone, boolean wasCancelled,
    @NotNull ArrayList<ResourceTiming> items, @NotNull ArrayList<ProviderTiming> interfaceShare) {

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
    public static final class ResourceTiming extends ResourceDescription {

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
            @NotNull String displayName, int componentCount, int damage, @NotNull ResourceType resourceType,
            long timeSpentOn, long craftedTotal, double shareInCraftingTime, double shareInCraftingTimeCombined,
            double craftsPerSec, @NotNull ArrayList<Timing> timings, @Nullable IconMappings.Reference icon,
            @Nullable StableKey itemKey, @NotNull List<String> providers) {
            super(registryNamespace, registryPath, displayName, componentCount, damage, resourceType, itemKey, icon);
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

    public static @NotNull CraftingHistory capture(@NotNull CompletedCrafting info, @Nullable IconMappings mappings) {
        long elapsed = info.timeDone() - info.timeStarted();
        long total = 0;
        for (CompletedCrafting.Resource resource : info.resources()) total += resource.timeSpentOn();
        ArrayList<ResourceTiming> items = new ArrayList<>();
        for (CompletedCrafting.Resource measured : info.resources()) {
            long spent = measured.timeSpentOn();
            ResourceSnapshot resource = measured.snapshot();
            long craftedTotal = resource.quantity();
            double share = total == 0 ? 1d : (double) spent / total;
            double combinedShare = elapsed > 0 ? Math.min((double) spent / elapsed, 1d) : 0d;
            double rate = spent > 0 ? craftedTotal / (spent / (double) TimeUnit.SECONDS.toMillis(1)) : 0d;
            ArrayList<Timing> timings = new ArrayList<>();
            for (CompletedCrafting.Interval interval : measured.intervals())
                timings.add(new Timing(interval.started(), interval.ended()));
            items.add(
                new ResourceTiming(
                    resource.description()
                        .registryNamespace(),
                    resource.description()
                        .registryPath(),
                    resource.description()
                        .displayName(),
                    resource.description()
                        .componentCount(),
                    resource.description()
                        .damage(),
                    resource.description()
                        .resourceType(),
                    spent,
                    craftedTotal,
                    share,
                    combinedShare,
                    rate,
                    timings,
                    resolveIcon(resource, mappings),
                    resource.description()
                        .itemKey(),
                    measured.providers()));
        }
        items.sort((first, second) -> Double.compare(second.shareInCraftingTime, first.shareInCraftingTime));
        ArrayList<ProviderTiming> interfaceShare = new ArrayList<>();
        for (CompletedCrafting.Provider provider : info.providers()) {
            ArrayList<Timing> timings = new ArrayList<>();
            long combined = 0;
            for (CompletedCrafting.Interval interval : provider.intervals()) {
                timings.add(new Timing(interval.started(), interval.ended()));
                combined += interval.ended() - interval.started();
            }
            interfaceShare
                .add(new ProviderTiming(provider.name(), timings, combined, new HashSet<>(provider.locations())));
        }
        interfaceShare.sort((first, second) -> Long.compare(second.timingsCombined(), first.timingsCombined()));
        return new CraftingHistory(
            new ResourceStack(info.finalOutput(), mappings),
            info.timeStarted(),
            info.timeDone(),
            info.wasCancelled(),
            items,
            interfaceShare);
    }

    private static @Nullable IconMappings.Reference resolveIcon(@NotNull ResourceSnapshot resource,
        @Nullable IconMappings mappings) {
        return mappings == null || resource.description()
            .itemKey() == null ? null
                : mappings.resolve(
                    resource.description()
                        .itemKey(),
                    resource.description()
                        .baseKey());
    }
}
