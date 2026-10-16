package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** One bounded detached-data worker shared by all grids; never accesses native game state. */
public final class StorageTrackingService implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

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
            }) {

            @Override
            protected void terminated() {
                try {
                    store.close();
                } catch (IOException failure) {
                    LOG.warn("Storage history could not be closed", failure);
                }
            }
        };
        worker.prestartCoreThread();
    }

    public void open(@NotNull StableKey gridKey, long generation) {
        if (closed) throw new IllegalStateException("Storage tracking is closed");
        State previous = states.get(gridKey);
        if (previous != null && previous.generation == generation) return;
        states.put(gridKey, new State(generation));
    }

    /** Never waits for disk or queue capacity. False requires a new full observation and flow coverage gap. */
    public boolean submit(@NotNull StableKey gridKey, long generation, @NotNull StorageObservation observation,
        boolean persist) {
        return submit(gridKey, generation, observation, persist, persist);
    }

    /** Live publication, durable stock sampling and flow buckets have independent schedules. */
    public boolean submit(@NotNull StableKey gridKey, long generation, @NotNull StorageObservation observation,
        boolean persistStock, boolean persistFlow) {
        if (persistStock && !observation.stockObserved())
            throw new IllegalArgumentException("A stock sample requires observed stock");
        State state = states.get(gridKey);
        if (closed || state == null || state.generation != generation) return false;
        long cost = 1L + observation.rows()
            .size()
            + observation.flows()
                .size();
        if (cost > maxQueuedRows || !capacity.tryAcquire((int) cost)) {
            invalidate(gridKey, generation);
            return false;
        }
        try {
            long lossEpoch = state.lossEpoch.get();
            worker.execute(() -> {
                try {
                    if (!closed) apply(gridKey, state, observation, persistStock, persistFlow, lossEpoch);
                } catch (RuntimeException failed) {
                    if (!state.workerFailureLogged) {
                        state.workerFailureLogged = true;
                        LOG.warn(
                            "Storage history worker failed for network {} generation {}",
                            gridKey,
                            generation,
                            failed);
                    }
                    invalidate(gridKey, generation);
                } finally {
                    capacity.release((int) cost);
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            capacity.release((int) cost);
            invalidate(gridKey, generation);
            return false;
        }
    }

    public @Nullable StorageSnapshot snapshot(@NotNull StableKey gridKey) {
        State state = states.get(gridKey);
        if (state == null) return null;
        StorageSnapshot snapshot = state.snapshot;
        long lossEpoch = state.lossEpoch.get();
        if (lossEpoch == snapshot.lossEpoch()) return snapshot;
        return new StorageSnapshot(
            snapshot.generation(),
            snapshot.revision(),
            snapshot.observedAt(),
            false,
            false,
            snapshot.persistenceComplete(),
            snapshot.rows(),
            lossEpoch);
    }

    /** Blocking detached-store query: call on HTTP/background threads, never the game thread. */
    public @NotNull StorageHistory history(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis,
        int limit) throws IOException {
        StorageHistory result = store.read(gridKey, itemKey, fromMillis, limit);
        State state = states.get(gridKey);
        boolean complete = result.complete() && (state == null || state.snapshot.persistenceComplete());
        return new StorageHistory(result.points(), complete, result.truncated(), result.baseline());
    }

    /** Records a history coverage gap without affecting live terminal subscriptions. */
    public void invalidate(@NotNull StableKey gridKey, long generation) {
        State state = states.get(gridKey);
        if (state == null || state.generation != generation) return;
        state.lossEpoch.incrementAndGet();
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

    private void apply(StableKey gridKey, State state, StorageObservation observation, boolean persistStock,
        boolean persistFlow, long lossEpoch) {
        boolean gap = lossEpoch != state.handledLossEpoch;
        Map<StableKey, StorageObservation.Row> next = state.rows;
        if (observation.stockObserved()) {
            next = observation.full() ? new HashMap<>() : new HashMap<>(state.rows);
            List<StorageObservation.Row> unkeyed = new ArrayList<>();
            for (StorageObservation.Row row : observation.rows()) {
                StableKey key = row.resource()
                    .itemKey();
                boolean present = row.resource()
                    .quantity() != 0 || row.craftable();
                if (key == null) {
                    if (present) unkeyed.add(row);
                } else if (present) next.put(key, row);
                else next.remove(key);
            }
            state.unkeyed = unkeyed;
        }
        boolean persistence = state.snapshot.persistenceComplete();
        if (persistence && (persistStock || persistFlow)) {
            List<StorageHistoryPoint> points = new ArrayList<>();
            boolean initialStock = persistStock && state.sampledRows == null;
            boolean initialFlow = persistFlow && !state.flowStarted;
            if (gap || !observation.flowComplete() || initialStock || initialFlow)
                points.add(point(state, observation, StorageHistoryPoint.Kind.GAP, null, false, 0, 0, false));
            if (persistStock) {
                for (Map.Entry<StableKey, StorageObservation.Row> entry : next.entrySet()) {
                    StorageObservation.Row row = entry.getValue();
                    if (state.sampledRows == null || !row.equals(state.sampledRows.get(entry.getKey()))) points.add(
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
                if (state.sampledRows != null) {
                    for (Map.Entry<StableKey, StorageObservation.Row> old : state.sampledRows.entrySet()) {
                        if (!next.containsKey(old.getKey())) points.add(
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
                points.add(
                    point(
                        state,
                        observation,
                        StorageHistoryPoint.Kind.OBSERVATION,
                        null,
                        false,
                        0,
                        0,
                        observation.full() || state.snapshot.stockComplete() && !gap));
            }
            if (persistFlow) {
                for (StorageObservation.Flow flow : observation.flows()) {
                    if (flow.resource()
                        .itemKey() != null)
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
                        StorageHistoryPoint.Kind.FLOW_OBSERVATION,
                        null,
                        false,
                        0,
                        0,
                        observation.flowComplete() && !gap));
            }
            try {
                store.append(gridKey, points);
                if (persistStock) state.sampledRows = new HashMap<>(next);
                if (persistFlow) state.flowStarted = true;
            } catch (IOException failure) {
                // A failed store stays failed until the owner reopens this generation. Live capture remains useful.
                persistence = false;
                LOG.warn(
                    "Storage history recording stopped for network {} generation {}",
                    gridKey,
                    state.generation,
                    failure);
            }
        }
        state.rows = next;
        StorageSnapshot previous = state.snapshot;
        List<StorageObservation.Row> visible = previous.rows();
        if (observation.stockObserved()) {
            visible = new ArrayList<>(next.values());
            visible.addAll(state.unkeyed);
        }
        state.snapshot = new StorageSnapshot(
            state.generation,
            previous.revision() + (observation.stockObserved() ? 1 : 0),
            observation.stockObserved() ? observation.observedAt() : previous.observedAt(),
            observation.full() || previous.stockComplete() && !gap,
            previous.flowComplete() && observation.flowComplete() && !gap,
            persistence,
            visible,
            lossEpoch);
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
        private boolean flowStarted;
        private boolean workerFailureLogged;
        private @Nullable Map<StableKey, StorageObservation.Row> sampledRows;
        private List<StorageObservation.Row> unkeyed = Collections.emptyList();
        private Map<StableKey, StorageObservation.Row> rows = new HashMap<>();
        private volatile StorageSnapshot snapshot;

        private State(long generation) {
            this.generation = generation;
            snapshot = new StorageSnapshot(generation, 0, 0, false, true, true, Collections.emptyList());
        }
    }
}
