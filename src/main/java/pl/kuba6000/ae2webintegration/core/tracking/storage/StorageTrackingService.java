package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** One bounded detached-data worker shared by all grids; never accesses native game state. */
public final class StorageTrackingService implements AutoCloseable {

    private final IStorageHistoryStore store;
    private final Map<StableKey, State> states = new ConcurrentHashMap<>();
    private final Semaphore capacity;
    private final int maxQueuedRows;
    private final ThreadPoolExecutor worker;
    private volatile boolean closed;

    public StorageTrackingService(@NotNull IStorageHistoryStore store, int maxQueuedRows) {
        if (maxQueuedRows < 1) throw new IllegalArgumentException("Queue limit must be positive");
        this.store = store;
        this.maxQueuedRows = maxQueuedRows;
        capacity = new Semaphore(maxQueuedRows);
        worker = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.min(maxQueuedRows, 1024)),
            task -> {
                Thread thread = new Thread(task, "AE2Web-StorageHistory");
                thread.setDaemon(true);
                return thread;
            });
    }

    public void open(@NotNull StableKey gridKey, long generation) {
        if (closed) throw new IllegalStateException("Storage tracking is closed");
        states
            .compute(gridKey, (key, old) -> old != null && old.generation == generation ? old : new State(generation));
    }

    /** Never waits for disk or queue capacity. False requires a new full observation and flow coverage gap. */
    public boolean submit(@NotNull StableKey gridKey, long generation, @NotNull StorageObservation observation,
        boolean persist) {
        State state = states.get(gridKey);
        if (closed || state == null || state.generation != generation) return false;
        long cost = 1L + observation.rows()
            .size()
            + observation.flows()
                .size();
        if (cost > maxQueuedRows || !capacity.tryAcquire((int) cost)) {
            state.lossEpoch.incrementAndGet();
            return false;
        }
        try {
            long lossEpoch = state.lossEpoch.get();
            worker.execute(() -> {
                try {
                    if (!closed) apply(gridKey, state, observation, persist, lossEpoch);
                } catch (RuntimeException failed) {
                    state.lossEpoch.incrementAndGet();
                } finally {
                    capacity.release((int) cost);
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            capacity.release((int) cost);
            state.lossEpoch.incrementAndGet();
            return false;
        }
    }

    public @Nullable StorageSnapshot snapshot(@NotNull StableKey gridKey) {
        State state = states.get(gridKey);
        if (state == null) return null;
        StorageSnapshot snapshot = state.snapshot;
        if (state.lossEpoch.get() == state.handledLossEpoch) return snapshot;
        return new StorageSnapshot(
            snapshot.generation(),
            snapshot.revision(),
            snapshot.observedAt(),
            false,
            false,
            snapshot.persistenceComplete(),
            snapshot.rows());
    }

    /** Blocking detached-store query: call on HTTP/background threads, never the game thread. */
    public @NotNull StorageHistory history(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis,
        int limit) throws IOException {
        StorageHistory result = store.read(gridKey, itemKey, fromMillis, limit);
        State state = states.get(gridKey);
        boolean complete = result.complete() && (state == null || state.snapshot.persistenceComplete());
        return new StorageHistory(result.points(), complete, result.truncated());
    }

    public void retire(@NotNull StableKey gridKey, long generation) {
        State state = states.get(gridKey);
        if (state != null && state.generation == generation) states.remove(gridKey, state);
    }

    @Override
    public void close() {
        if (closed) return;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread()
                .interrupt();
        } finally {
            closed = true;
            states.clear();
        }
    }

    private void apply(StableKey gridKey, State state, StorageObservation observation, boolean persist,
        long lossEpoch) {
        boolean gap = lossEpoch != state.handledLossEpoch;
        boolean startHistory = persist && !state.persisting;
        Map<StableKey, StorageObservation.Row> next = observation.full() ? new HashMap<>() : new HashMap<>(state.rows);
        List<StorageHistoryPoint> points = new ArrayList<>();
        Set<StableKey> reported = new HashSet<>();
        if (gap || !observation.flowComplete() || state.snapshot.revision() == 0 || startHistory) {
            points.add(point(state, observation, StorageHistoryPoint.Kind.GAP, null, false, 0, 0, false));
        }
        for (StorageObservation.Row row : observation.rows()) {
            StableKey key = row.resource()
                .itemKey();
            if (key == null) throw new IllegalArgumentException("Tracked resource requires a stable identity");
            reported.add(key);
            StorageObservation.Row old = state.rows.get(key);
            boolean present = row.resource()
                .quantity() != 0 || row.craftable();
            if (present) next.put(key, row);
            else next.remove(key);
            if (!startHistory && !row.equals(old) && (present || old != null)) {
                points.add(
                    point(
                        state,
                        observation,
                        StorageHistoryPoint.Kind.STOCK,
                        row.resource(),
                        row.craftable(),
                        0,
                        0,
                        true));
            }
        }
        if (startHistory) {
            for (StorageObservation.Row row : next.values()) {
                points.add(
                    point(
                        state,
                        observation,
                        StorageHistoryPoint.Kind.STOCK,
                        row.resource(),
                        row.craftable(),
                        0,
                        0,
                        true));
            }
        } else if (observation.full()) {
            for (Map.Entry<StableKey, StorageObservation.Row> old : state.rows.entrySet()) {
                if (!next.containsKey(old.getKey()) && !reported.contains(old.getKey())) {
                    points.add(
                        point(
                            state,
                            observation,
                            StorageHistoryPoint.Kind.STOCK,
                            zero(
                                old.getValue()
                                    .resource()),
                            false,
                            0,
                            0,
                            true));
                }
            }
        }
        for (StorageObservation.Flow flow : observation.flows()) {
            points.add(
                point(
                    state,
                    observation,
                    StorageHistoryPoint.Kind.FLOW,
                    flow.resource(),
                    false,
                    flow.input(),
                    flow.output(),
                    observation.flowComplete() && !gap));
        }
        points.add(
            point(
                state,
                observation,
                StorageHistoryPoint.Kind.OBSERVATION,
                null,
                false,
                0,
                0,
                observation.flowComplete() && !gap));
        boolean persistence = state.snapshot.persistenceComplete();
        state.persisting = persist;
        if (persist) {
            try {
                store.append(gridKey, points);
            } catch (IOException failure) {
                persistence = false;
                state.persisting = false;
            }
        }
        state.rows = next;
        state.snapshot = new StorageSnapshot(
            state.generation,
            state.snapshot.revision() + 1,
            observation.observedAt(),
            observation.full() || state.snapshot.stockComplete() && !gap,
            state.snapshot.flowComplete() && observation.flowComplete() && !gap,
            persistence,
            new ArrayList<>(next.values()));
        state.handledLossEpoch = lossEpoch;
    }

    private static StorageHistoryPoint point(State state, StorageObservation observation, StorageHistoryPoint.Kind kind,
        @Nullable ResourceSnapshot resource, boolean craftable, long input, long output, boolean complete) {
        return new StorageHistoryPoint(
            observation.observedAt(),
            state.generation,
            kind,
            resource,
            craftable,
            input,
            output,
            complete);
    }

    private static ResourceSnapshot zero(ResourceSnapshot previous) {
        return new ResourceSnapshot(
            previous.registryNamespace(),
            previous.registryPath(),
            previous.componentCount(),
            previous.damage(),
            previous.displayName(),
            previous.resourceType(),
            0,
            previous.itemKey(),
            previous.iconBaseKey());
    }

    private static final class State {

        private final long generation;
        private final AtomicLong lossEpoch = new AtomicLong();
        private volatile long handledLossEpoch;
        private boolean persisting;
        private Map<StableKey, StorageObservation.Row> rows = new HashMap<>();
        private volatile StorageSnapshot snapshot;

        private State(long generation) {
            this.generation = generation;
            snapshot = new StorageSnapshot(generation, 0, 0, false, true, true, Collections.emptyList());
        }
    }
}
