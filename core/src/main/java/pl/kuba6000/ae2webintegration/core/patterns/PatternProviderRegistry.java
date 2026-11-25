package pl.kuba6000.ae2webintegration.core.patterns;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.live.LiveSubscription;
import pl.kuba6000.ae2webintegration.core.live.LiveView;

/** Detached publications and atomic subscription reservation, guarded by the shared hub monitor. */
public final class PatternProviderRegistry {

    private final LiveHub hub;

    PatternProviderRegistry(LiveHub hub) {
        this.hub = hub;
    }

    @Desugar
    public record Bootstrap(@Nullable String subscription, @NotNull LiveView view,
        @NotNull List<PatternProvider> providers) {}

    /** An authorized request waiting for its baseline. Close in finally, including on timeout or cancellation. */
    public static final class Opening implements AutoCloseable {

        private final LiveHub hub;
        private final PatternProviderTopic topic;
        private boolean closed;

        private Opening(LiveHub hub, PatternProviderTopic topic) {
            this.hub = hub;
            this.topic = topic;
            topic.openings++;
        }

        /** HTTP worker only. Never waits with the hub monitor held. */
        public boolean awaitReady(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
            if (!topic.baseline.await(timeout, unit)) return false;
            synchronized (hub) {
                return !closed && hub.topic(topic.key()) == topic && topic.initialized();
            }
        }

        @Override
        public void close() {
            synchronized (hub) {
                if (closed) return;
                closed = true;
                topic.openings--;
            }
        }
    }

    @Nullable
    Opening open(StableKey grid, long generation, long now) {
        synchronized (hub) {
            hub.activate(
                PatternProviderTopic.keyOf(grid),
                generation,
                now,
                () -> new PatternProviderTopic(grid, generation));
            PatternProviderTopic topic = topic(grid, generation);
            return topic == null ? null : new Opening(hub, topic);
        }
    }

    boolean exists(StableKey grid, long generation) {
        synchronized (hub) {
            return topic(grid, generation) != null;
        }
    }

    boolean opening(StableKey grid, long generation) {
        synchronized (hub) {
            PatternProviderTopic topic = topic(grid, generation);
            return topic != null && topic.openings > 0;
        }
    }

    void observed(StableKey grid, long generation, PatternProviderReader.Delta delta) {
        synchronized (hub) {
            PatternProviderTopic topic = topic(grid, generation);
            if (topic != null) topic.observed(delta);
        }
    }

    private @Nullable PatternProviderTopic topic(StableKey grid, long generation) {
        PatternProviderTopic topic = (PatternProviderTopic) hub.topic(PatternProviderTopic.keyOf(grid));
        return topic != null && topic.generation() == generation ? topic : null;
    }

    /** A live request which exceeds the shared admission limits receives no snapshot. */
    public @Nullable Bootstrap snapshot(@NotNull Opening opening, @Nullable AuthSession session, boolean live,
        boolean icons, @Nullable String packId, @Nullable String replace, long now) {
        synchronized (hub) {
            PatternProviderTopic topic = opening.topic;
            if (opening.closed || topic != hub.topic(topic.key()) || !topic.initialized()) return null;
            LiveSubscription sub = live ? hub.reserve(topic.key(), session, icons, packId, replace, now) : null;
            if (live && sub == null) return null;
            return new Bootstrap(sub == null ? null : sub.token(), topic.view(), topic.snapshot());
        }
    }

    public boolean renew(@NotNull StableKey grid, @Nullable AuthSession session, @NotNull String token, long now) {
        return hub.renew(PatternProviderTopic.keyOf(grid), session, token, now);
    }

    public void cancel(@NotNull String token) {
        hub.cancel(token);
    }
}
