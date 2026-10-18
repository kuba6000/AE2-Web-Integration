package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
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
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

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

    /** Live publication, durable stock sampling and flow buckets have independent schedules. */
    public boolean submit(@NotNull StableKey gridKey, long generation, @NotNull StorageObservation observation,
        boolean persistStock, boolean persistFlow, long flowFrom, int retentionDays) {
        if (persistStock && !observation.stockObserved())
            throw new IllegalArgumentException("A stock sample requires observed stock");
        State state = states.get(gridKey);
        if (closed || state == null || state.generation != generation) return false;
        long cost = 1L + observation.unkeyed()
            .size()
            + observation.rows()
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
                    if (!closed) apply(
                        gridKey,
                        state,
                        observation,
                        persistStock,
                        persistFlow,
                        flowFrom,
                        retentionDays,
                        lossEpoch);
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

    /** Queue policy edits with measurements; callers may await completion only off the game thread. */
    public @NotNull Future<?> setRetention(@NotNull StableKey gridKey, int retentionDays) throws IOException {
        try {
            return worker.submit(() -> {
                store.setRetention(gridKey, retentionDays);
                return null;
            });
        } catch (RejectedExecutionException rejected) {
            throw new IOException("History policy update was not accepted", rejected);
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
            snapshot.descriptions(),
            snapshot.unkeyed(),
            lossEpoch);
    }

    /** Blocking detached-store query: call on HTTP/background threads, never the game thread. */
    public @NotNull StorageHistory history(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis,
        int limit) throws IOException {
        StorageHistory result = store.read(gridKey, itemKey, fromMillis, limit);
        State state = states.get(gridKey);
        boolean complete = result.complete() && (state == null || state.snapshot.persistenceComplete());
        return new StorageHistory(
            result.points(),
            result.descriptions(),
            complete,
            result.truncated(),
            result.baseline());
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
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) stopWorker();
        } catch (InterruptedException interrupted) {
            stopWorker();
            Thread.currentThread()
                .interrupt();
        } finally {
            closed = true;
            states.clear();
        }
    }

    private void stopWorker() {
        for (Runnable pending : worker.shutdownNow()) {
            if (pending instanceof Future<?>) ((Future<?>) pending).cancel(false);
        }
    }

    private void apply(StableKey gridKey, State state, StorageObservation observation, boolean persistStock,
        boolean persistFlow, long flowFrom, int retentionDays, long lossEpoch) {
        boolean gap = lossEpoch != state.handledLossEpoch;
        if (gap || !observation.flowComplete() || persistFlow && !state.flowStarted) state.needsStockBaseline = true;
        Map<StableKey, StorageObservation.Row> next = state.mergeStock(observation);
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>(state.snapshot.descriptions());
        descriptions.putAll(observation.descriptions());

        boolean persistence = state.snapshot.persistenceComplete();
        if (persistence && (persistStock || persistFlow)) {
            List<StorageHistoryPoint> points = state
                .historyPoints(observation, next, persistStock, persistFlow, flowFrom, gap);
            Map<StableKey, ResourceDescriptor> writtenDescriptions = descriptionsFor(points, observation, descriptions);
            try {
                store.append(gridKey, writtenDescriptions, points, retentionDays);
                // Only a successful append advances the durable baseline.
                if (persistStock) {
                    Map<StableKey, Long> sampled = new HashMap<>();
                    for (StorageObservation.Row row : next.values()) {
                        // Preserve a known zero only while its row remains live, for recovery after a gap.
                        if (row.quantity() != 0
                            || state.sampledQuantities != null && state.sampledQuantities.containsKey(row.itemKey()))
                            sampled.put(row.itemKey(), row.quantity());
                    }
                    state.sampledQuantities = sampled;
                    state.needsStockBaseline = !(observation.full() || state.snapshot.stockComplete() && !gap);
                }
                if (persistFlow) state.flowStarted = true;
            } catch (IOException failure) {
                // A failed store stays failed until a new generation is opened. Live capture remains useful.
                persistence = false;
                LOG.warn(
                    "Storage history recording stopped for network {} generation {}",
                    gridKey,
                    state.generation,
                    failure);
            }
        }
        state.publish(observation, next, descriptions, persistence, lossEpoch);
    }

    private static Map<StableKey, ResourceDescriptor> descriptionsFor(List<StorageHistoryPoint> points,
        StorageObservation observation, Map<StableKey, ResourceDescriptor> retained) {
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>(observation.descriptions());
        for (StorageHistoryPoint point : points) {
            StableKey key = point.itemKey();
            if (key != null && !descriptions.containsKey(key)) descriptions.put(key, retained.get(key));
        }
        return descriptions;
    }

    /** The worker owns stock and sampling state; other threads only record loss or read the immutable snapshot. */
    private static final class State {

        private final long generation;
        private final AtomicLong lossEpoch = new AtomicLong();
        private volatile long handledLossEpoch;
        private boolean flowStarted;
        private boolean needsStockBaseline;
        private boolean workerFailureLogged;
        private @Nullable Map<StableKey, Long> sampledQuantities;
        private List<StorageObservation.UnkeyedRow> unkeyed = Collections.emptyList();
        private Map<StableKey, StorageObservation.Row> rows = new HashMap<>();
        private volatile StorageSnapshot snapshot;

        private State(long generation) {
            this.generation = generation;
            snapshot = new StorageSnapshot(
                generation,
                0,
                0,
                false,
                true,
                true,
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyList());
        }

        private Map<StableKey, StorageObservation.Row> mergeStock(StorageObservation observation) {
            if (!observation.stockObserved()) return rows;
            Map<StableKey, StorageObservation.Row> next = observation.full() ? new HashMap<>() : new HashMap<>(rows);
            for (StorageObservation.Row row : observation.rows()) {
                if (row.quantity() != 0 || row.craftable()) next.put(row.itemKey(), row);
                else next.remove(row.itemKey());
            }
            unkeyed = new ArrayList<>();
            for (StorageObservation.UnkeyedRow row : observation.unkeyed()) {
                if (row.resource()
                    .quantity() != 0 || row.craftable()) unkeyed.add(row);
            }
            return next;
        }

        private List<StorageHistoryPoint> historyPoints(StorageObservation observation,
            Map<StableKey, StorageObservation.Row> next, boolean persistStock, boolean persistFlow, long flowFrom,
            boolean gap) {
            List<StorageHistoryPoint> points = new ArrayList<>();
            boolean initialStock = persistStock && sampledQuantities == null;
            boolean initialFlow = persistFlow && !flowStarted;
            boolean completeStock = observation.full() || snapshot.stockComplete() && !gap;
            if (gap || !observation.flowComplete() || initialStock || initialFlow || persistStock && !completeStock)
                points.add(point(observation, StorageHistoryPoint.Kind.GAP, null, 0, 0, 0));
            if (persistStock) appendStockPoints(points, observation, next, gap);
            if (persistFlow) appendFlowPoints(points, observation, flowFrom, gap);
            return points;
        }

        private void appendStockPoints(List<StorageHistoryPoint> points, StorageObservation observation,
            Map<StableKey, StorageObservation.Row> next, boolean gap) {
            for (StorageObservation.Row row : next.values()) {
                Long previous = sampledQuantities == null ? null : sampledQuantities.get(row.itemKey());
                if (row.quantity() != (previous == null ? 0 : previous)
                    || needsStockBaseline && observation.full() && previous != null)
                    points.add(point(observation, StorageHistoryPoint.Kind.STOCK, row.itemKey(), row.quantity(), 0, 0));
            }
            if (sampledQuantities != null) {
                for (Map.Entry<StableKey, Long> sampled : sampledQuantities.entrySet()) {
                    if (sampled.getValue() != 0 && !next.containsKey(sampled.getKey()))
                        points.add(point(observation, StorageHistoryPoint.Kind.STOCK, sampled.getKey(), 0, 0, 0));
                }
            }
            if (observation.full() || snapshot.stockComplete() && !gap)
                points.add(point(observation, StorageHistoryPoint.Kind.OBSERVATION, null, 0, 0, 0));
        }

        private void appendFlowPoints(List<StorageHistoryPoint> points, StorageObservation observation, long flowFrom,
            boolean gap) {
            boolean complete = observation.flowComplete() && !gap;
            for (StorageObservation.Flow flow : observation.flows()) {
                points.add(
                    new StorageHistoryPoint(
                        observation.observedAt(),
                        generation,
                        StorageHistoryPoint.Kind.FLOW,
                        flow.itemKey(),
                        0,
                        flow.input(),
                        flow.output(),
                        flowFrom));
            }
            if (complete) points.add(
                new StorageHistoryPoint(
                    observation.observedAt(),
                    generation,
                    StorageHistoryPoint.Kind.FLOW_OBSERVATION,
                    null,
                    0,
                    0,
                    0,
                    flowFrom));
        }

        private void publish(StorageObservation observation, Map<StableKey, StorageObservation.Row> next,
            Map<StableKey, ResourceDescriptor> descriptions, boolean persistence, long observedLossEpoch) {
            boolean gap = observedLossEpoch != handledLossEpoch;
            rows = next;
            StorageSnapshot previous = snapshot;
            List<StorageObservation.Row> visible = previous.rows();
            if (observation.stockObserved()) visible = new ArrayList<>(next.values());
            // Unsampled removals still need their description when the next durable zero sample is written.
            descriptions.keySet()
                .removeIf(
                    key -> !next.containsKey(key)
                        && (sampledQuantities == null || !sampledQuantities.containsKey(key)));
            snapshot = new StorageSnapshot(
                generation,
                previous.revision() + (observation.stockObserved() ? 1 : 0),
                observation.stockObserved() ? observation.observedAt() : previous.observedAt(),
                observation.full() || previous.stockComplete() && !gap,
                previous.flowComplete() && observation.flowComplete() && !gap,
                persistence,
                visible,
                descriptions,
                unkeyed,
                observedLossEpoch);
            handledLossEpoch = observedLossEpoch;
        }

        private StorageHistoryPoint point(StorageObservation observation, StorageHistoryPoint.Kind kind,
            @Nullable StableKey itemKey, long quantity, long input, long output) {
            return new StorageHistoryPoint(
                observation.observedAt(),
                generation,
                kind,
                itemKey,
                quantity,
                input,
                output,
                null);
        }
    }
}
