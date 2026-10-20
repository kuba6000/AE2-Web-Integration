package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Worker-owned recent measurements, fed exclusively by configured tracking samples, independent of database
 * availability.
 */
public final class RecentResourceActivity {

    public static final long FLOW_WINDOW_MILLIS = 120_000;
    public static final long STOCK_WINDOW_MILLIS = 3_600_000;
    private static final int MAX_STOCK_SAMPLES = 3601;
    public static final long BUCKET_MILLIS = 5_000;
    private final boolean stockEnabled;
    private final boolean flowEnabled;
    private final int maximumResources;
    private final Map<StableKey, Entry> resources = new HashMap<>();
    private final ArrayDeque<Long> stockSamples = new ArrayDeque<>();
    private @Nullable Long flowObservedAt;
    private @Nullable Long flowSince;
    private long flowLostAt = Long.MIN_VALUE;
    private long stockLostAt = Long.MIN_VALUE;
    private long observedAt;

    public RecentResourceActivity(boolean stockEnabled, boolean flowEnabled, int maximumResources, long startedAt) {
        this.stockEnabled = stockEnabled;
        this.flowEnabled = flowEnabled;
        this.maximumResources = maximumResources;
        flowSince = flowEnabled ? startedAt : null;
        flowObservedAt = flowSince;
    }

    /** Decimal strings retain exact quantities in JavaScript, including totals exceeding a signed long. */
    @Desugar
    public record Amount(@Nullable String input, @Nullable String output, @Nullable String stockChange) {}

    /** Since fields identify the available observation period; null means the measurement is unavailable. */
    @Desugar
    public record Summary(long flowWindowMillis, long stockWindowMillis, long bucketMillis, long observedAt,
        @Nullable Long flowObservedAt, @Nullable Long stockObservedAt, @Nullable Long flowSince,
        @Nullable Long stockSince, boolean flowComplete, boolean stockComplete,
        @NotNull Map<StableKey, Amount> resources) {

        public Summary {
            resources = Collections.unmodifiableMap(new HashMap<>(resources));
        }

        public @NotNull Summary withResources(@NotNull Map<StableKey, Amount> amounts) {
            return new Summary(
                flowWindowMillis,
                stockWindowMillis,
                bucketMillis,
                observedAt,
                flowObservedAt,
                stockObservedAt,
                flowSince,
                stockSince,
                flowComplete,
                stockComplete,
                amounts);
        }
    }

    /** Terminal observations must not be passed as tracking samples. */
    public void observe(@NotNull StorageObservation observation, boolean stockSample, boolean flowSample) {
        advance(observation.observedAt());
        if (flowEnabled && flowSample) {
            recordFlow(observation);
            flowObservedAt = observation.observedAt();
        }
        if (stockEnabled && stockSample) recordStock(observation);
    }

    /** Expiring rolling flow never reads native storage or manufactures a new measurement. */
    public void advance(long now) {
        if (now < observedAt) reset(now);
        observedAt = now;
        prune(now);
    }

    private void recordFlow(StorageObservation observation) {
        long now = observation.observedAt();
        if (!observation.flowComplete()) flowLostAt = now;
        for (StorageObservation.Flow flow : observation.flows()) {
            Entry entry = entry(flow.itemKey(), now);
            if (entry == null) continue;
            Bucket bucket = entry.bucket(now);
            bucket.input = bucket.input.add(BigInteger.valueOf(flow.input()));
            bucket.output = bucket.output.add(BigInteger.valueOf(flow.output()));
        }
        if (!stockEnabled) for (StorageObservation.Row row : observation.rows()) entry(row.itemKey(), now);
    }

    private void recordStock(StorageObservation observation) {
        long now = observation.observedAt();
        if (!observation.stockObserved()) return;
        boolean initial = stockSamples.isEmpty();
        if (initial && !observation.full()) return;
        Set<StableKey> remaining = observation.full() ? new HashSet<>(resources.keySet()) : null;
        for (StorageObservation.Row row : observation.rows()) {
            Entry entry = entry(row.itemKey(), now);
            if (entry == null) continue;
            if (remaining != null) remaining.remove(row.itemKey());
            entry.stock(row.quantity(), now, initial);
        }
        if (remaining != null) for (StableKey key : remaining) resources.get(key)
            .stock(0, now, initial);
        if (stockSamples.isEmpty() || stockSamples.getLast() != now) stockSamples.addLast(now);
        long cutoff = now - STOCK_WINDOW_MILLIS;
        while (stockSamples.size() > 1 && (stockSamples.getFirst() < cutoff || stockSamples.size() > MAX_STOCK_SAMPLES))
            stockSamples.removeFirst();
        for (Entry entry : resources.values()) entry.pruneStock(stockSamples.getFirst());
        if (!observation.unkeyed()
            .isEmpty()) stockLostAt = now;
    }

    /** A native topology/read gap invalidates stock baselines without borrowing flow or durable history. */
    public void reset(long now) {
        resources.clear();
        stockSamples.clear();
        flowSince = flowEnabled ? now : null;
        flowObservedAt = flowSince;
        stockLostAt = now;
        flowLostAt = now;
        observedAt = now;
    }

    public @NotNull Summary snapshot() {
        Map<StableKey, Amount> amounts = new HashMap<>();
        for (Map.Entry<StableKey, Entry> resource : resources.entrySet()) {
            Entry entry = resource.getValue();
            BigInteger input = BigInteger.ZERO;
            BigInteger output = BigInteger.ZERO;
            for (Bucket bucket : entry.buckets) {
                input = input.add(bucket.input);
                output = output.add(bucket.output);
            }
            amounts.put(
                resource.getKey(),
                new Amount(
                    flowEnabled ? input.toString() : null,
                    flowEnabled ? output.toString() : null,
                    stockEnabled && stockSamples.size() > 1 && entry.stockKnown
                        ? Long.toString(entry.quantity - entry.baseline)
                        : null));
        }
        long cutoff = observedAt - FLOW_WINDOW_MILLIS;
        Long stockSince = stockSamples.peekFirst();
        Long stockObservedAt = stockSamples.peekLast();
        return new Summary(
            FLOW_WINDOW_MILLIS,
            STOCK_WINDOW_MILLIS,
            BUCKET_MILLIS,
            observedAt,
            flowObservedAt,
            stockObservedAt,
            flowSince,
            stockSince,
            flowEnabled && flowLostAt < cutoff,
            stockEnabled && stockSince != null && stockLostAt < stockSince,
            amounts);
    }

    private @Nullable Entry entry(StableKey key, long now) {
        Entry entry = resources.get(key);
        if (entry != null) return entry;
        if (resources.size() >= maximumResources) {
            flowLostAt = now;
            stockLostAt = now;
            return null;
        }
        entry = new Entry();
        entry.stockKnown = !stockSamples.isEmpty();
        resources.put(key, entry);
        return entry;
    }

    private void prune(long now) {
        long cutoff = now - FLOW_WINDOW_MILLIS;
        Iterator<Entry> entries = resources.values()
            .iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next();
            while (!entry.buckets.isEmpty() && entry.buckets.getFirst().at + BUCKET_MILLIS <= cutoff) {
                entry.buckets.removeFirst();
            }
            if (entry.buckets.isEmpty() && entry.stockPoints.isEmpty() && entry.quantity == 0 && entry.baseline == 0)
                entries.remove();
        }
    }

    private static final class Entry {

        private final ArrayDeque<Bucket> buckets = new ArrayDeque<>();
        private final ArrayDeque<StockPoint> stockPoints = new ArrayDeque<>();
        private boolean stockKnown;
        private long quantity;
        private long baseline;

        private Bucket bucket(long now) {
            long at = now - Math.floorMod(now, BUCKET_MILLIS);
            if (buckets.isEmpty() || buckets.getLast().at != at) buckets.addLast(new Bucket(at));
            return buckets.getLast();
        }

        private void stock(long amount, long now, boolean initial) {
            if (initial) baseline = amount;
            if (!stockKnown || quantity != amount) stockPoints.addLast(new StockPoint(now, amount));
            quantity = amount;
            stockKnown = true;
        }

        private void pruneStock(long since) {
            while (!stockPoints.isEmpty() && stockPoints.getFirst()
                .at() <= since) {
                baseline = stockPoints.removeFirst()
                    .quantity();
            }
        }
    }

    @Desugar
    private record StockPoint(long at, long quantity) {}

    private static final class Bucket {

        private final long at;
        private BigInteger input = BigInteger.ZERO;
        private BigInteger output = BigInteger.ZERO;

        private Bucket(long at) {
            this.at = at;
        }
    }
}
