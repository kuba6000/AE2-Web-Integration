package pl.kuba6000.ae2webintegration.core.patterns;

import java.util.List;

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

    void observed(PatternProviderTopic topic, PatternProviderReader.Delta delta) {
        synchronized (hub) {
            if (hub.topic(topic.key()) == topic) topic.observed(delta);
        }
    }

    /** A live request which exceeds the shared admission limits receives no snapshot. */
    public @Nullable Bootstrap snapshot(@NotNull PatternProviderTopic topic, @Nullable AuthSession session,
        boolean live, boolean icons, @Nullable String packId, @Nullable String replace, long now) {
        synchronized (hub) {
            if (hub.topic(topic.key()) != topic || !topic.initialized()) return null;
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
