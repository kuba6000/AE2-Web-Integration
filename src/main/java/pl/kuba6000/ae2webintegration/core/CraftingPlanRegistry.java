package pl.kuba6000.ae2webintegration.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

/** Owns the runtime lifecycle of web-created crafting plans for one grid. */
public final class CraftingPlanRegistry {

    private static final int MAX_PLANS = 3;
    private static final long RETENTION_NANOS = TimeUnit.MINUTES.toNanos(15);
    private static final long NOT_COMPLETED_OBSERVED = Long.MIN_VALUE;

    private static final class Entry {

        private final Future<IAECraftingJob> plan;
        private final AtomicLong lastCompletedAccessNanos = new AtomicLong(NOT_COMPLETED_OBSERVED);

        private Entry(Future<IAECraftingJob> plan) {
            this.plan = plan;
        }

    }

    private final LongSupplier nanoClock;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, Entry> plans = new ConcurrentHashMap<>();

    public CraftingPlanRegistry(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    public int add(Future<IAECraftingJob> plan) {
        int id = nextId.getAndIncrement();
        plans.put(id, new Entry(plan));
        plans.remove(id - MAX_PLANS);
        return id;
    }

    public Future<IAECraftingJob> find(int id) {
        Entry entry = plans.get(id);
        if (entry == null) {
            return null;
        }
        if (entry.plan.isDone()) {
            entry.lastCompletedAccessNanos.set(nanoClock.getAsLong());
        }
        return entry.plan;
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
