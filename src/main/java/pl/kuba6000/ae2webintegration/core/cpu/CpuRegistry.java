package pl.kuba6000.ae2webintegration.core.cpu;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.live.LiveSubscription;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;
import pl.kuba6000.ae2webintegration.core.live.LiveView;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Crafting CPU views of all grids, one {@link CpuTopic} each, on the shared {@link LiveHub}. Every method takes the
 * hub monitor, which is never held across encoding, native reads or socket writes.
 */
public final class CpuRegistry {

    private final LiveHub hub;

    /** Detached state at {@code at}, the server clock time its running measurements are evaluated for. */
    @Desugar
    public record Bootstrap(@NotNull String subscription, @NotNull LiveView view, @NotNull CpuObservation.Header header,
        @NotNull List<CpuObservation.Row> rows, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<CpuObservation.UnkeyedRow> unkeyed, long at) {}

    public CpuRegistry(@NotNull LiveHub hub) {
        this.hub = hub;
    }

    /** Keeps the view of this exact generation alive for a reservation period. Called only while acquiring a viewer. */
    public void activate(@NotNull StableKey grid, @NotNull StableKey cpu, long generation, long now) {
        hub.activate(CpuTopic.keyOf(grid, cpu), generation, now, () -> new CpuTopic(grid, cpu, generation));
    }

    /** A full observation initializes the view; a restricted one only updates an initialized view. */
    public void observed(@NotNull StableKey grid, @NotNull StableKey cpu, long generation,
        @NotNull CpuObservation observation) {
        synchronized (hub) {
            CpuTopic topic = topic(grid, cpu, generation);
            if (topic != null) topic.observed(observation);
        }
    }

    /** Snapshot and subscription registration share the same lock; no change can fall between them. */
    public @Nullable Bootstrap reserve(@NotNull StableKey grid, @NotNull StableKey cpu, @Nullable AuthSession session,
        boolean icons, @Nullable String packId, @Nullable String replace, long now) {
        synchronized (hub) {
            LiveSubscription subscription = hub
                .reserve(CpuTopic.keyOf(grid, cpu), session, icons, packId, replace, now);
            return subscription == null ? null : ((CpuTopic) subscription.topic()).bootstrap(subscription.token(), now);
        }
    }

    /** A client acknowledges its live stream independently of successful server-side socket writes. */
    public boolean renew(@NotNull StableKey grid, @NotNull StableKey cpu, @Nullable AuthSession session,
        @NotNull String token, long now) {
        return hub.renew(CpuTopic.keyOf(grid, cpu), session, token, now);
    }

    public void cancel(@NotNull String token) {
        hub.cancel(token);
    }

    /** A job change or a lost CPU ends the viewers; they fetch a fresh snapshot. */
    public void invalidate(@NotNull StableKey grid, @NotNull StableKey cpu, long generation) {
        hub.invalidate(CpuTopic.keyOf(grid, cpu), generation);
    }

    public void retire(@NotNull StableKey grid, @NotNull StableKey cpu, long generation) {
        hub.retire(CpuTopic.keyOf(grid, cpu), generation);
    }

    /** Active reservations and streams keep observation alive without transport driving native reads. */
    public @NotNull Set<LiveTopic.Key> viewed(long now) {
        return hub.viewed(CpuTopic.class, now);
    }

    private @Nullable CpuTopic topic(StableKey grid, StableKey cpu, long generation) {
        CpuTopic topic = (CpuTopic) hub.topic(CpuTopic.keyOf(grid, cpu));
        return topic == null || topic.generation() != generation ? null : topic;
    }
}
