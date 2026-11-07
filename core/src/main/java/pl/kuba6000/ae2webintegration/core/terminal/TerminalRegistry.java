package pl.kuba6000.ae2webintegration.core.terminal;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;

/**
 * Storage terminals of all grids, one {@link StorageTopic} each, on the shared {@link LiveHub}. Every method takes the
 * hub monitor, which is never held across encoding, native reads or socket writes.
 */
public final class TerminalRegistry {

    private final LiveHub hub;
    private final AtomicLong recentTokens = new AtomicLong();

    @Desugar
    public record Bootstrap(@NotNull String subscription, @NotNull LiveView view,
        @NotNull List<StorageObservation.Row> rows, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<StorageObservation.UnkeyedRow> unkeyed, @Nullable RecentResourceActivity.Summary recent) {}

    public TerminalRegistry(@NotNull LiveHub hub) {
        this.hub = hub;
    }

    /** Returns true when a new baseline is needed. Called only while acquiring/renewing a viewer. */
    public boolean activate(@NotNull StableKey key, long generation, long now) {
        return hub.activate(
            StorageTopic.keyOf(key),
            generation,
            now,
            () -> new StorageTopic(key, generation, recentTokens::incrementAndGet));
    }

    /** Applies only observed keys except for a full reconciliation, which must also find removed rows. */
    public void observed(@NotNull StableKey key, long generation, @NotNull StorageObservation observation) {
        synchronized (hub) {
            StorageTopic topic = topic(key, generation);
            if (topic != null) topic.observed(observation);
        }
    }

    /** Snapshot and subscription registration share the same lock; no change can fall between them. */
    public @Nullable Bootstrap reserve(@NotNull StableKey key, @Nullable AuthSession session, boolean icons,
        @Nullable String packId, @Nullable String replace, long now) {
        synchronized (hub) {
            LiveSubscription subscription = hub.reserve(StorageTopic.keyOf(key), session, icons, packId, replace, now);
            return subscription == null ? null : ((StorageTopic) subscription.topic()).bootstrap(subscription.token());
        }
    }

    /** A client acknowledges its live stream independently of successful server-side socket writes. */
    public boolean renew(@NotNull StableKey key, @Nullable AuthSession session, @NotNull String token, long now) {
        return hub.renew(StorageTopic.keyOf(key), session, token, now);
    }

    public void cancel(@NotNull String token) {
        hub.cancel(token);
    }

    /** Detached current membership for pin admission, without copying the entire inventory or reading native state. */
    public @Nullable ResourceDescriptor description(@NotNull StableKey key, @NotNull StableKey item) {
        synchronized (hub) {
            hub.expire(System.currentTimeMillis());
            StorageTopic topic = topic(key);
            return topic == null || !topic.initialized() ? null : topic.description(item);
        }
    }

    public @Nullable StorageSnapshot snapshot(@NotNull StableKey key) {
        synchronized (hub) {
            StorageTopic topic = topic(key);
            return topic == null || !topic.initialized() ? null : topic.snapshot();
        }
    }

    /** A token identifies this terminal lifetime and its current cache coverage, not only the native generation. */
    public long recentToken(@NotNull StableKey key, long generation) {
        synchronized (hub) {
            StorageTopic topic = topic(key, generation);
            return topic == null ? 0 : topic.recentToken();
        }
    }

    /** Reject late cache publication without waiting for a queue slot or rebuilding a summary. */
    public void invalidateRecent(@NotNull StableKey key, long generation) {
        synchronized (hub) {
            StorageTopic topic = topic(key, generation);
            if (topic != null) topic.invalidateRecent();
        }
    }

    /** Published alongside stock; detached immutable summaries never require a history query. */
    public void recent(@NotNull StableKey key, long generation, long publicationToken,
        @NotNull RecentResourceActivity.Summary summary) {
        synchronized (hub) {
            StorageTopic topic = topic(key, generation);
            if (topic != null) topic.recent(publicationToken, summary);
        }
    }

    public @Nullable RecentResourceActivity.Summary recent(@NotNull StableKey key) {
        synchronized (hub) {
            StorageTopic topic = topic(key);
            return topic == null ? null : topic.recent();
        }
    }

    /** Full-list invalidation is distinct from maintenance reconciliation and from history coverage loss. */
    public void invalidate(@NotNull StableKey key, long generation) {
        hub.invalidate(StorageTopic.keyOf(key), generation);
    }

    public void retire(@NotNull StableKey key, long generation) {
        hub.retire(StorageTopic.keyOf(key), generation);
    }

    /** Active reservations and streams keep observation alive without transport driving native reads. */
    public @NotNull Set<StableKey> viewedGrids(long now) {
        Set<StableKey> grids = new HashSet<>();
        for (LiveTopic.Key key : hub.viewed(StorageTopic.class, now)) grids.add(key.grid());
        return grids;
    }

    private @Nullable StorageTopic topic(StableKey key) {
        return (StorageTopic) hub.topic(StorageTopic.keyOf(key));
    }

    private @Nullable StorageTopic topic(StableKey key, long generation) {
        StorageTopic topic = topic(key);
        return topic == null || topic.generation() != generation ? null : topic;
    }
}
