package pl.kuba6000.ae2webintegration.core.live;

import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Shared state of one observed view, held by a {@link LiveHub}. Subclasses own their rows and are only touched while
 * holding the hub monitor, which also guards the subscriptions fed by {@link #publish}.
 */
public abstract class LiveTopic {

    private static final int MAX_PENDING_KEYS = 16_384;

    /** Identity of a topic: its kind, the grid it observes and, for kinds that observe part of a grid, the subject. */
    @Desugar
    public record Key(@NotNull Class<? extends LiveTopic> kind, @NotNull StableKey grid, @Nullable StableKey subject) {}

    final Set<LiveSubscription> subscribers = new HashSet<>();
    long activeUntil;
    private final Key key;
    private final long generation;
    private long epoch;
    private long revision;
    private boolean initialized;

    protected LiveTopic(@NotNull Key key, long generation) {
        this.key = key;
        this.generation = generation;
    }

    public final @NotNull Key key() {
        return key;
    }

    public final long generation() {
        return generation;
    }

    public final long revision() {
        return revision;
    }

    public final boolean initialized() {
        return initialized;
    }

    protected final void markInitialized() {
        initialized = true;
    }

    public final @NotNull LiveView view() {
        return new LiveView(generation + "." + epoch, Long.toString(revision));
    }

    /** Ends current subscribers and discards state; the next baseline starts a new epoch. */
    protected void invalidate() {
        for (LiveSubscription subscriber : subscribers) subscriber.end("invalidate");
        initialized = false;
        epoch++;
        revision = 0;
    }

    /**
     * Advances the revision and marks the changes pending for every attached subscriber. A subscriber whose pending
     * keys exceed the bound is ended with a reset instead of growing without limit.
     */
    protected final void publish(@NotNull Set<StableKey> changed, @NotNull Set<StableKey> definitions,
        boolean unkeyedChanged, @NotNull Set<StableKey> summaryKeys, boolean summaryChanged) {
        revision++;
        for (LiveSubscription subscriber : subscribers) {
            if (subscriber.end != null) continue;
            subscriber.accept(changed, definitions, unkeyedChanged, summaryKeys, summaryChanged);
            if (subscriber.pendingKeys() > MAX_PENDING_KEYS) subscriber.end("reset");
        }
    }

    /** Builds the detached update for a subscriber from its frozen pending changes and the current rows. */
    protected abstract @NotNull LiveUpdate drain(@NotNull LiveSubscription.Pending pending);
}
