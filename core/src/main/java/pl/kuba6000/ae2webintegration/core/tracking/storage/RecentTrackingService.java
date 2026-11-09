package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;

/** One bounded, detached-data cache worker; database writes cannot delay its measurements. */
public final class RecentTrackingService implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private final TerminalRegistry terminals;
    private final Map<StableKey, State> states = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Command> commands;
    private final AtomicLong queuedRows = new AtomicLong();
    private volatile int maximumRows;
    private final Thread worker;
    private final AtomicBoolean resetsPending = new AtomicBoolean();
    private volatile boolean closed;

    public RecentTrackingService(@NotNull TerminalRegistry terminals, int maximumRows) {
        if (maximumRows < 1) throw new IllegalArgumentException("Queue limit must be positive");
        this.terminals = terminals;
        this.maximumRows = maximumRows;
        commands = new ArrayBlockingQueue<>(1024);
        worker = new Thread(this::run, "AE2Web-RecentTracking");
        worker.setDaemon(true);
        worker.start();
    }

    /** Preserves accepted commands and cached measurements; lower limits govern subsequent admission. */
    public void applyLimits(int maximumRows, int maximumResources) {
        if (maximumRows < 1 || maximumResources < 1) throw new IllegalArgumentException("Limits must be positive");
        this.maximumRows = maximumRows;
        for (State state : states.values()) state.maximumResources = maximumResources;
    }

    public void open(@NotNull StableKey key, long generation, boolean stock, boolean flow, int maximumResources,
        long startedAt) {
        if (closed) throw new IllegalStateException("Recent tracking is closed");
        State previous = states.get(key);
        if (previous != null && previous.generation == generation) return;
        states.put(key, new State(generation, stock, flow, maximumResources, startedAt));
    }

    /** Nonblocking admission; rejected measurements invalidate coverage without borrowing the caller thread. */
    public boolean submit(@NotNull StableKey key, long generation, @NotNull StorageObservation observation,
        boolean stockSample, boolean flowSample) {
        State state = current(key, generation);
        if (state == null) return false;
        long cost = 1L + observation.rows()
            .size()
            + observation.flows()
                .size()
            + observation.unkeyed()
                .size();
        if (cost > maximumRows
            || !offer(key, state, observation, stockSample, flowSample, observation.observedAt(), (int) cost)) {
            invalidate(key, generation, observation.observedAt());
            return false;
        }
        return true;
    }

    /** Expiry and publication only; this command never captures another native measurement. */
    public boolean refresh(@NotNull StableKey key, long generation, long now) {
        State state = current(key, generation);
        return state != null && offer(key, state, null, false, false, now, 1);
    }

    /** Last immutable worker publication, independent of terminal rows, viewers and database availability. */
    public @Nullable RecentResourceActivity.Summary snapshot(@NotNull StableKey key) {
        State state = states.get(key);
        if (closed || state == null) return null;
        Published published = state.published;
        return published != null && published.lossEpoch() == state.lossEpoch.get()
            && current(key, state.generation) == state ? published.summary() : null;
    }

    private boolean offer(StableKey key, State state, @Nullable StorageObservation observation, boolean stock,
        boolean flow, long now, int cost) {
        if (queuedRows.addAndGet(cost) > maximumRows) {
            queuedRows.addAndGet(-cost);
            return false;
        }
        Command command = new Command(
            key,
            state,
            state.lossEpoch.get(),
            terminals.recentToken(key, state.generation),
            observation,
            stock,
            flow,
            now,
            cost);
        if (commands.offer(command)) return true;
        queuedRows.addAndGet(-cost);
        return false;
    }

    /** Control is immediate and cannot be lost when measurement admission is saturated. */
    public void invalidate(@NotNull StableKey key, long generation, long now) {
        State state = current(key, generation);
        if (state == null) return;
        state.lostAt = now;
        state.lossEpoch.incrementAndGet();
        terminals.invalidateRecent(key, generation);
        resetsPending.set(true);
    }

    public void retire(@NotNull StableKey key, long generation) {
        State state = states.get(key);
        if (state != null && state.generation == generation && states.remove(key, state))
            terminals.invalidateRecent(key, generation);
    }

    private @Nullable State current(StableKey key, long generation) {
        State state = states.get(key);
        return !closed && state != null && state.generation == generation ? state : null;
    }

    private void run() {
        while (!closed) {
            try {
                Command command = commands.poll(100, TimeUnit.MILLISECONDS);
                if (command != null) {
                    try {
                        apply(command);
                    } finally {
                        queuedRows.addAndGet(-command.cost());
                    }
                }
                // A rejected command may be the last activity on its network. Reset even without another sample.
                if (resetsPending.getAndSet(false)) for (Map.Entry<StableKey, State> entry : states.entrySet()) {
                    State state = entry.getValue();
                    if (state.handledLossEpoch != state.lossEpoch.get()) reset(entry.getKey(), state);
                }
            } catch (InterruptedException interrupted) {
                if (closed) break;
            } catch (RuntimeException failure) {
                LOG.warn("Recent tracking worker failed", failure);
            }
        }
        commands.clear();
    }

    private void apply(Command command) {
        State state = command.state();
        if (current(command.key(), state.generation) != state) return;
        if (state.handledLossEpoch != state.lossEpoch.get()) reset(command.key(), state);
        if (command.lossEpoch() != state.lossEpoch.get()) return;
        if (state.activity == null) state.activity = new RecentResourceActivity(
            state.stock,
            state.flow,
            state.maximumResources,
            state.startedAt);
        state.activity.setMaximumResources(state.maximumResources);
        try {
            if (command.observation() == null) state.activity.advance(command.now());
            else state.activity.observe(command.observation(), command.stock(), command.flow());
            publish(command.key(), state, command.lossEpoch(), command.publicationToken());
        } catch (RuntimeException failure) {
            invalidate(command.key(), state.generation, command.now());
            LOG.warn("Recent measurements failed for network {}", command.key(), failure);
        }
    }

    private void reset(StableKey key, State state) {
        long lossEpoch = state.lossEpoch.get();
        if (current(key, state.generation) != state) return;
        state.activity = new RecentResourceActivity(state.stock, state.flow, state.maximumResources, state.lostAt);
        state.activity.reset(state.lostAt);
        state.handledLossEpoch = lossEpoch;
        publish(key, state, lossEpoch, terminals.recentToken(key, state.generation));
    }

    private void publish(StableKey key, State state, long lossEpoch, long publicationToken) {
        RecentResourceActivity activity = state.activity;
        if (activity == null) return;
        RecentResourceActivity.Summary summary = activity.snapshot();
        if (current(key, state.generation) == state && state.lossEpoch.get() == lossEpoch) {
            state.published = new Published(lossEpoch, summary);
            if (publicationToken != 0) terminals.recent(key, state.generation, publicationToken, summary);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Map.Entry<StableKey, State> entry : states.entrySet())
            terminals.invalidateRecent(entry.getKey(), entry.getValue().generation);
        states.clear();
        worker.interrupt();
        try {
            worker.join(5000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
        }
    }

    @Desugar
    private record Command(StableKey key, State state, long lossEpoch, long publicationToken,
        @Nullable StorageObservation observation, boolean stock, boolean flow, long now, int cost) {}

    @Desugar
    private record Published(long lossEpoch, @NotNull RecentResourceActivity.Summary summary) {}

    /** Producer controls are primitives; all activity/summary creation belongs to the worker. */
    private static final class State {

        private final long generation;
        private final boolean stock;
        private final boolean flow;
        private volatile int maximumResources;
        private final long startedAt;
        private final AtomicLong lossEpoch = new AtomicLong();
        private volatile long lostAt;
        private long handledLossEpoch;
        private @Nullable RecentResourceActivity activity;
        private volatile @Nullable Published published;

        private State(long generation, boolean stock, boolean flow, int maximumResources, long startedAt) {
            this.generation = generation;
            this.stock = stock;
            this.flow = flow;
            this.maximumResources = maximumResources;
            this.startedAt = startedAt;
        }
    }
}
