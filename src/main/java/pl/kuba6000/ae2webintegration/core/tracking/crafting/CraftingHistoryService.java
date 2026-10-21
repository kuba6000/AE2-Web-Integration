package pl.kuba6000.ae2webintegration.core.tracking.crafting;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Bounded completed jobs await the world scheduler; SQL never runs in enqueue. */
public final class CraftingHistoryService implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final int MAX_PENDING_JOBS = 64;
    private final HsqlCraftingHistoryStore store;
    private final Executor executor;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final ReentrantLock drain = new ReentrantLock();
    private boolean scheduled;
    private boolean closed;

    @Desugar
    private record Pending(@NotNull StableKey grid, @NotNull CompletedCrafting job) {}

    public CraftingHistoryService(@NotNull HsqlCraftingHistoryStore store, @NotNull Executor executor) {
        this.store = store;
        this.executor = executor;
    }

    /** Ownership transfers to this service on success; a rejected job is logged and never partially persisted. */
    public boolean enqueue(@NotNull StableKey grid, @NotNull CompletedCrafting job) {
        synchronized (pending) {
            if (closed || pending.size() >= MAX_PENDING_JOBS) {
                LOG.error(
                    "Crafting history rejected completed job for grid {}: {}",
                    grid,
                    closed ? "service is closed" : "pending queue is full");
                return false;
            }
            pending.addLast(new Pending(grid, job));
            if (!scheduled) {
                scheduled = true;
                try {
                    executor.execute(this::writePending);
                } catch (RejectedExecutionException failure) {
                    scheduled = false;
                    LOG.error("Crafting history worker unavailable; completed jobs await maintenance retry", failure);
                }
            }
            return true;
        }
    }

    private void writePending() {
        try {
            while (true) {
                flush();
                synchronized (pending) {
                    if (pending.isEmpty()) {
                        scheduled = false;
                        return;
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (pending) {
                scheduled = false;
            }
            LOG.error("Crafting history write failed; completed jobs remain pending for retry", failure);
        }
    }

    /** Worker/maintenance/shutdown drain; persistence failures retain the job, malformed payloads are dropped. */
    public void flush() throws IOException {
        drain.lock();
        try {
            while (true) {
                Pending next;
                synchronized (pending) {
                    next = pending.peekFirst();
                }
                if (next == null) return;
                try {
                    store.append(next.grid(), next.job());
                } catch (RejectedCraftingException failure) {
                    LOG.error(
                        "Crafting history rejected completed job for grid {}: {}",
                        next.grid(),
                        failure.getMessage());
                }
                synchronized (pending) {
                    pending.removeFirst();
                }
            }
        } finally {
            drain.unlock();
        }
    }

    public void maintain() throws IOException {
        flush();
        store.maintain();
    }

    public @NotNull HsqlCraftingHistoryStore.Page list(@NotNull StableKey grid, @Nullable Long before, int limit)
        throws IOException {
        return store.list(grid, before, limit);
    }

    public @Nullable CompletedCrafting read(@NotNull StableKey grid, long id) throws IOException {
        return store.read(grid, id);
    }

    @Override
    public void close() throws IOException {
        synchronized (pending) {
            closed = true;
        }
        try {
            flush();
        } catch (IOException | RuntimeException failure) {
            synchronized (pending) {
                LOG.error("Crafting history shutdown left {} completed jobs unwritten", pending.size(), failure);
            }
            throw failure;
        }
    }
}
