package pl.kuba6000.ae2webintegration.core.grid;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingDependencyGraph;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
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
        private volatile boolean retained = true;
        private @Nullable TreePreparation tree;
        private final AtomicLong lastCompletedAccessNanos = new AtomicLong(NOT_COMPLETED_OBSERVED);

        private Entry(@NotNull Future<IAECraftingJob> plan, @NotNull ResourceSnapshot finalOutput,
            @NotNull CraftingOptions options, boolean autoStart) {
            this.plan = plan;
            this.finalOutput = finalOutput;
            this.options = options;
            this.autoStart = autoStart;
        }

        public boolean isRetained() {
            return retained;
        }

        public synchronized @Nullable TreePreparation tree() {
            return tree;
        }

        /** Server-thread only; native traversal runs outside lifecycle/publication locks. */
        public @Nullable TreePreparation captureTree(int maxEntries, int maxCharacters)
            throws InterruptedException, ExecutionException {
            TreePreparation preparation;
            synchronized (this) {
                if (!retained || !plan.isDone()) return null;
                if (tree != null) return tree;
                preparation = new TreePreparation();
                tree = preparation;
            }
            try {
                CraftingRecipeCapture captured = plan.get()
                    .web$captureRecipes(maxEntries, maxCharacters);
                preparation.capture.complete(captured);
            } catch (InterruptedException | ExecutionException | RuntimeException | Error failure) {
                preparation.capture.completeExceptionally(failure);
                throw failure;
            }
            return preparation;
        }

        private synchronized void retire() {
            retained = false;
            tree = null;
        }

    }

    /** One detached capture and normalized graph shared by viewers of the exact retained entry. */
    public static final class TreePreparation {

        private final String id = UUID.randomUUID()
            .toString();
        private final CompletableFuture<CraftingRecipeCapture> capture = new CompletableFuture<>();
        private @Nullable CraftingDependencyGraph graph;

        public @NotNull String id() {
            return id;
        }

        public boolean isCaptured() {
            return capture.isDone();
        }

        /** HTTP-worker only, after capture completion. Null is a stable unsupported capability. */
        public synchronized @Nullable CraftingDependencyGraph graph() {
            if (!capture.isDone()) throw new IllegalStateException("Capture is still pending");
            if (graph == null) {
                CraftingRecipeCapture captured = capture.getNow(null);
                if (captured != null) graph = CraftingDependencyGraph.from(captured);
            }
            return graph;
        }
    }

    private static void retire(@Nullable Entry entry) {
        if (entry != null) entry.retire();
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
        retire(plans.put(id, new Entry(plan, finalOutput, options, autoStart)));
        retire(plans.remove(id - MAX_PLANS));
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
        entry.retire();
        entry.plan.cancel(true);
        return true;
    }

    public void remove(int id) {
        retire(plans.remove(id));
    }

    public void clearForServerStop() {
        for (Entry entry : plans.values()) {
            entry.retire();
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
                if (plans.remove(planEntry.getKey(), entry)) entry.retire();
            }
        }
    }
}
