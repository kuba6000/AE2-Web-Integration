package pl.kuba6000.ae2webintegration.core.tracking.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Detached completed measurements; collections are owned by the history worker after capture. */
@Desugar
public record CompletedCrafting(@NotNull ResourceSnapshot finalOutput, long timeStarted, long timeDone,
    boolean wasCancelled, @NotNull List<Resource> resources, @NotNull List<Provider> providers,
    @NotNull List<PlayerIdentity> requesters) {

    @Desugar
    public record Interval(long started, long ended) {}

    @Desugar
    public record Resource(@NotNull ResourceSnapshot snapshot, long timeSpentOn, @NotNull List<Interval> intervals,
        @NotNull List<String> providers) {}

    @Desugar
    public record Provider(@NotNull String name, @NotNull List<DimensionalCoords> locations,
        @NotNull List<Interval> intervals) {}

    /**
     * On the server thread after active tracking ends, transfer interval-list ownership without copying the timeline.
     */
    public static @NotNull CompletedCrafting capture(@NotNull AE2JobTracker.JobTrackingInfo info) {
        List<Resource> resources = new ArrayList<>();
        for (Map.Entry<IAEKey, Long> entry : info.timeSpentOn.entrySet()) {
            IAEKey key = entry.getKey();
            Set<String> recordedProviders = info.resourceProviders.get(key);
            List<String> providers = recordedProviders == null ? new ArrayList<>() : new ArrayList<>(recordedProviders);
            Collections.sort(providers);
            resources.add(
                new Resource(info.resourceSnapshots.get(key), entry.getValue(), info.itemShare.get(key), providers));
        }
        List<Provider> providers = new ArrayList<>();
        for (Map.Entry<AE2JobTracker.AEInterface, ArrayList<Interval>> entry : info.interfaceShare.entrySet()) {
            providers
                .add(new Provider(entry.getKey().name, new ArrayList<>(entry.getKey().location), entry.getValue()));
        }
        return new CompletedCrafting(
            info.finalOutput,
            info.timeStarted,
            info.timeDone,
            info.wasCancelled,
            resources,
            providers,
            info.getRequesters());
    }

}
