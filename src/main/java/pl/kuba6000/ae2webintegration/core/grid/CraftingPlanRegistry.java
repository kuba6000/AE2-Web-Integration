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

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.crafting.AggregateTreeReconstruction;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingDependencyGraph;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceGraph;
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
                IAECraftingJob job = plan.get();
                CraftingTraceCapture trace = job.web$captureTrace(maxEntries, maxCharacters);
                preparation.capture.complete(
                    trace != null && trace.root() != null ? new Captured(trace, null)
                        : new Captured(null, job.web$captureRecipes(maxEntries, maxCharacters)));
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

    /** What a job could capture: its per-occurrence trace, else its recipe aggregate, else nothing. */
    @Desugar
    private record Captured(@Nullable CraftingTraceCapture trace, @Nullable CraftingRecipeCapture recipes) {}

    /**
     * One detached capture and normalized graph shared by viewers of the exact retained entry. A job that
     * records a trace is shown as one; otherwise the aggregate recipe capture is used.
     */
    public static final class TreePreparation {

        private static final int MAX_RECONSTRUCTED_ENTRIES = 100_000;

        private boolean prepared;
        private final String id = UUID.randomUUID()
            .toString();
        private final CompletableFuture<Captured> capture = new CompletableFuture<>();
        private @Nullable CraftingTraceGraph traceGraph;
        private @Nullable CraftingDependencyGraph graph;

        public @NotNull String id() {
            return id;
        }

        public boolean isCaptured() {
            return capture.isDone();
        }

        /** HTTP-worker only. Native trace or reconstructed aggregate occurrence view, shared by viewers. */
        public synchronized @Nullable CraftingTraceGraph traceGraph() {
            prepare();
            return traceGraph;
        }

        /** HTTP-worker only. Declaration fallback when occurrence reconstruction is unavailable. */
        public synchronized @Nullable CraftingDependencyGraph graph() {
            prepare();
            return graph;
        }

        /** Provenance remains native only when the adapter supplied an actual trace. */
        public boolean isNativeTrace() {
            return completed().trace() != null;
        }

        private void prepare() {
            if (prepared) return;
            Captured captured = completed();
            CraftingTraceCapture trace = captured.trace();
            if (trace == null && captured.recipes() != null)
                trace = AggregateTreeReconstruction.from(captured.recipes(), MAX_RECONSTRUCTED_ENTRIES);
            if (trace != null) traceGraph = CraftingTraceGraph.from(trace);
            else if (captured.recipes() != null) graph = CraftingDependencyGraph.from(captured.recipes());
            prepared = true;
        }

        private Captured completed() {
            if (!capture.isDone()) throw new IllegalStateException("Capture is still pending");
            return capture.getNow(null);
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
