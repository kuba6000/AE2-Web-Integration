package pl.kuba6000.ae2webintegration.core.live;

import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * One viewer of a topic, bound to the exact login session. Pending changes are collapsed to the latest state per key;
 * all fields except {@code end} are guarded by the hub monitor.
 */
public final class LiveSubscription {

    /** Changes since the last drain; ownership passes to the caller. */
    @Desugar
    public record Pending(@NotNull Set<StableKey> changed, @NotNull Set<StableKey> definitions, boolean unkeyedChanged,
        @NotNull Set<StableKey> summaryKeys, boolean summaryChanged) {}

    final String token;
    final LiveTopic topic;
    final WebPrincipal principal;
    final AuthSession session;
    final boolean icons;
    final @Nullable String packId;
    long expires;
    boolean attached;
    volatile @Nullable String end;
    private Set<StableKey> changed = new HashSet<>();
    private Set<StableKey> definitions = new HashSet<>();
    private Set<StableKey> summaryKeys = new HashSet<>();
    private boolean unkeyedChanged;
    private boolean summaryChanged;

    LiveSubscription(String token, LiveTopic topic, AuthSession session, boolean icons, @Nullable String packId,
        long expires) {
        this.token = token;
        this.topic = topic;
        this.principal = session.principal();
        this.session = session;
        this.icons = icons;
        this.packId = packId;
        this.expires = expires;
    }

    public @NotNull String token() {
        return token;
    }

    public @NotNull LiveTopic topic() {
        return topic;
    }

    void accept(Set<StableKey> changedKeys, Set<StableKey> definitionKeys, boolean unkeyed, Set<StableKey> summary,
        boolean summaryUpdated) {
        changed.addAll(changedKeys);
        definitions.addAll(definitionKeys);
        summaryKeys.addAll(summary);
        unkeyedChanged |= unkeyed;
        summaryChanged |= summaryUpdated;
    }

    int pendingKeys() {
        return Math.max(changed.size(), summaryKeys.size());
    }

    boolean hasPending() {
        return !changed.isEmpty() || unkeyedChanged || summaryChanged;
    }

    Pending take() {
        Pending pending = new Pending(changed, definitions, unkeyedChanged, summaryKeys, summaryChanged);
        changed = new HashSet<>();
        definitions = new HashSet<>();
        summaryKeys = new HashSet<>();
        unkeyedChanged = false;
        summaryChanged = false;
        return pending;
    }

    /** Ends the subscription and drops its pending changes. */
    void end(String reason) {
        end = reason;
        clearPending();
    }

    void clearPending() {
        changed.clear();
        definitions.clear();
        summaryKeys.clear();
        unkeyedChanged = false;
        summaryChanged = false;
    }
}
