package pl.kuba6000.ae2webintegration.core.grid;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Owns the runtime lifecycle of web-created crafting plans for one grid. */
public final class CraftingPlanRegistry {

    private static final int MAX_PLANS = 3;
    private static final long RETENTION_NANOS = TimeUnit.MINUTES.toNanos(15);
    private static final long NOT_COMPLETED_OBSERVED = Long.MIN_VALUE;

    /** A calculation and its detached original request, retained and removed together. */
    public static final class Entry {

        public final @NotNull Future<IAECraftingJob> plan;
        public final @NotNull ResourceSnapshot finalOutput;
        public final @NotNull CraftingOptions options;
        public final boolean autoStart;
        private final AtomicLong lastCompletedAccessNanos = new AtomicLong(NOT_COMPLETED_OBSERVED);

        private Entry(@NotNull Future<IAECraftingJob> plan, @NotNull ResourceSnapshot finalOutput,
            @NotNull CraftingOptions options, boolean autoStart) {
            this.plan = plan;
            this.finalOutput = finalOutput;
            this.options = options;
            this.autoStart = autoStart;
        }

    }

    private final LongSupplier nanoClock;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, Entry> plans = new ConcurrentHashMap<>();

    public CraftingPlanRegistry(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    public int add(@NotNull Future<IAECraftingJob> plan, @NotNull ResourceSnapshot finalOutput,
        @NotNull CraftingOptions options, boolean autoStart) {
        int id = nextId.getAndIncrement();
        plans.put(id, new Entry(plan, finalOutput, options, autoStart));
        plans.remove(id - MAX_PLANS);
        return id;
    }

    public @Nullable Entry find(int id) {
        Entry entry = plans.get(id);
        if (entry == null) {
            return null;
        }
        if (entry.plan.isDone()) {
            entry.lastCompletedAccessNanos.set(nanoClock.getAsLong());
        }
        return entry;
    }

    public boolean cancel(int id) {
        Entry entry = plans.remove(id);
        if (entry == null) {
            return false;
        }
        entry.plan.cancel(true);
        return true;
    }

    public void remove(int id) {
        plans.remove(id);
    }

    public void clearForServerStop() {
        for (Entry entry : plans.values()) {
            if (!entry.plan.isDone()) {
                entry.plan.cancel(true);
            }
        }
        plans.clear();
        nextId.set(1);
    }

    public void evictExpiredCompleted() {
        evictExpiredCompleted(nanoClock.getAsLong());
    }

    void evictExpiredCompleted(long nowNanos) {
        for (Map.Entry<Integer, Entry> planEntry : plans.entrySet()) {
            Entry entry = planEntry.getValue();
            if (!entry.plan.isDone()) {
                continue;
            }
            long lastAccess = entry.lastCompletedAccessNanos.get();
            if (lastAccess == NOT_COMPLETED_OBSERVED) {
                entry.lastCompletedAccessNanos.compareAndSet(NOT_COMPLETED_OBSERVED, nowNanos);
            } else if (nowNanos - lastAccess >= RETENTION_NANOS) {
                plans.remove(planEntry.getKey(), entry);
            }
        }
    }
}
