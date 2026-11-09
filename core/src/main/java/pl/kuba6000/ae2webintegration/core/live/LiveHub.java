package pl.kuba6000.ae2webintegration.core.live;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.config.Config;

/**
 * The single monitor guarding every live topic and subscription, with the admission limits shared by all topic kinds.
 * Topics and subscriptions are only read or changed while holding this monitor, which is never held while encoding,
 * reading native state or writing to a socket. Detached frozen data is what leaves it.
 */
public final class LiveHub {

    private static final int MAX_TOPICS = 64;
    private static final int MAX_SUBSCRIPTIONS = 64;
    private static final int MAX_PER_VIEWER = 6;
    private static final long RESERVATION_MILLIS = 10_000;
    private static final long STREAM_LEASE_MILLIS = 90_000;
    private boolean closed;
    private final Map<LiveTopic.Key, LiveTopic> topics = new HashMap<>();
    private final Map<String, LiveSubscription> subscriptions = new HashMap<>();

    /**
     * Keeps the topic of this exact generation alive for a reservation period, creating it when absent or older.
     * Returns true when a new baseline is needed. Called only while acquiring/renewing a viewer.
     */
    public synchronized boolean activate(@NotNull LiveTopic.Key key, long generation, long now,
        @NotNull Supplier<? extends LiveTopic> create) {
        if (closed) return false;
        expire(now);
        LiveTopic current = topics.get(key);
        if (current != null && current.generation() == generation) {
            current.activeUntil = now + RESERVATION_MILLIS;
            return !current.initialized();
        }
        if (current != null && current.generation() > generation) return false;
        if (current != null) retire(key, current.generation());
        if (topics.size() >= MAX_TOPICS) return false;
        LiveTopic created = create.get();
        created.activeUntil = now + RESERVATION_MILLIS;
        topics.put(key, created);
        return true;
    }

    /** The current topic of any generation. Callers hold the monitor for as long as they use it. */
    public synchronized @Nullable LiveTopic topic(@NotNull LiveTopic.Key key) {
        return topics.get(key);
    }

    /**
     * Registers a subscription to an initialized topic. Callers take their detached snapshot while still holding the
     * monitor, so no change can fall between the snapshot and the subscription.
     */
    public synchronized @Nullable LiveSubscription reserve(@NotNull LiveTopic.Key key, @Nullable AuthSession session,
        boolean icons, @Nullable String packId, @Nullable String replace, long now) {
        expire(now);
        if (session == null || !AuthService.isValid(session, now)) return null;
        LiveTopic topic = topics.get(key);
        if (closed || topic == null || !topic.initialized()) return null;
        LiveSubscription previous = subscriptions.get(replace);
        boolean replaces = previous != null && previous.session == session;
        int released = replaces ? 1 : 0;
        if (subscriptions.size() - released >= MAX_SUBSCRIPTIONS) return null;
        boolean publicMode = Config.INSTANCE.general.publicMode;
        long count = subscriptions.values()
            .stream()
            .filter(s -> publicMode ? s.principal.equals(session.principal()) : s.session == session)
            .count();
        if (count - released >= MAX_PER_VIEWER) return null;
        if (replaces) {
            previous.end("reset");
            release(previous);
        }
        String token = UUID.randomUUID()
            .toString();
        LiveSubscription subscription = new LiveSubscription(
            token,
            topic,
            session,
            icons,
            packId,
            now + RESERVATION_MILLIS);
        subscriptions.put(token, subscription);
        topic.subscribers.add(subscription);
        return subscription;
    }

    /** Tokens are single-use and bound to the authenticated login session, topic and requested representation. */
    synchronized @Nullable LiveSubscription attach(String token, LiveTopic.Key key, @Nullable AuthSession session,
        boolean icons, long now) {
        expire(now);
        LiveSubscription subscription = subscriptions.get(token);
        if (subscription == null || subscription.attached
            || subscription.end != null
            || !subscription.topic.key()
                .equals(key)
            || subscription.session != session
            || subscription.icons != icons) return null;
        subscription.attached = true;
        subscription.expires = now + STREAM_LEASE_MILLIS;
        return subscription;
    }

    synchronized boolean hasPending(LiveSubscription subscription) {
        return subscription.hasPending();
    }

    /** A client acknowledges its live stream independently of successful server-side socket writes. */
    public synchronized boolean renew(@NotNull LiveTopic.Key key, @Nullable AuthSession session, @NotNull String token,
        long now) {
        expire(now);
        LiveSubscription subscription = subscriptions.get(token);
        if (closed || subscription == null
            || !subscription.attached
            || subscription.end != null
            || subscription.session != session
            || !subscription.topic.key()
                .equals(key))
            return false;
        subscription.expires = now + STREAM_LEASE_MILLIS;
        return true;
    }

    /** Frozen detached data leaves the lock; changes during encoding/writing create a new pending batch. */
    synchronized @Nullable LiveUpdate drain(LiveSubscription subscription) {
        if (subscription.end != null || !subscription.hasPending()) return null;
        return subscription.topic.drain(subscription.take());
    }

    synchronized void release(LiveSubscription subscription) {
        subscriptions.remove(subscription.token, subscription);
        subscription.topic.subscribers.remove(subscription);
        subscription.clearPending();
    }

    /** Releases a reservation that was never attached to a stream. */
    public synchronized void cancel(@NotNull String token) {
        LiveSubscription subscription = subscriptions.get(token);
        if (subscription != null && !subscription.attached) release(subscription);
    }

    /** Full invalidation is distinct from maintenance reconciliation; clients fetch a fresh snapshot. */
    public synchronized void invalidate(@NotNull LiveTopic.Key key, long generation) {
        LiveTopic topic = topics.get(key);
        if (topic == null || topic.generation() != generation) return;
        topic.invalidate();
    }

    public synchronized void retire(@NotNull LiveTopic.Key key, long generation) {
        LiveTopic topic = topics.get(key);
        if (topic == null || topic.generation() != generation) return;
        invalidate(key, generation);
        topics.remove(key);
    }

    public synchronized void expire(long now) {
        for (LiveSubscription subscription : subscriptions.values()) {
            if (!AuthService.isValid(subscription.session, now)) subscription.end("revoked");
            else if (subscription.attached && subscription.expires <= now) subscription.end("reset");
        }
        Iterator<LiveSubscription> expired = subscriptions.values()
            .iterator();
        while (expired.hasNext()) {
            LiveSubscription subscription = expired.next();
            if (subscription.end == null && (subscription.attached || subscription.expires > now)) continue;
            expired.remove();
            subscription.topic.subscribers.remove(subscription);
        }
        Iterator<LiveTopic> idle = topics.values()
            .iterator();
        while (idle.hasNext()) {
            LiveTopic topic = idle.next();
            if (topic.activeUntil <= now && topic.subscribers.isEmpty()) idle.remove();
        }
    }

    /** Active reservations and streams keep observation alive without transport driving native reads. */
    public synchronized @NotNull Set<LiveTopic.Key> viewed(@NotNull Class<? extends LiveTopic> kind, long now) {
        expire(now);
        Set<LiveTopic.Key> keys = new HashSet<>();
        for (LiveSubscription subscription : subscriptions.values()) {
            LiveTopic.Key key = subscription.topic.key();
            if (subscription.end == null && key.kind() == kind) keys.add(key);
        }
        return keys;
    }

    synchronized void reset() {
        for (LiveSubscription subscription : subscriptions.values()) subscription.end("invalidate");
        topics.clear();
        subscriptions.clear();
    }

    synchronized void close() {
        closed = true;
        reset();
    }
}
