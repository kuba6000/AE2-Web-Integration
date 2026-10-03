package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** One assembly owner consumes detached batches while the client captures the next batch. */
final class PackExportWriter {

    private static final int PAGE_SIZE = 1024;
    private static final int MAX_BUFFERED_BATCHES = 2;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AE2 web icon pack writer");
        thread.setDaemon(true);
        return thread;
    });

    private enum Publication {
        OPEN,
        CANCELED,
        COMMITTING,
        COMPLETE
    }

    private final AtomicReference<Publication> publication = new AtomicReference<>(Publication.OPEN);
    private final AtomicInteger batches = new AtomicInteger();
    private volatile boolean initialized;
    private volatile @Nullable Throwable failure;
    private @Nullable Future<?> terminal;
    private @Nullable IconPackWriter writer;
    private @Nullable Path completed;
    private int maxBufferedBatches;
    private long assemblyWorkNanos;
    private long assemblyBatches;

    PackExportWriter(@NotNull Path root, @NotNull ExportEnvironment environment) {
        submit(() -> {
            writer = new IconPackWriter(
                root,
                environment.metadata(() -> publication.get() == Publication.CANCELED),
                PAGE_SIZE);
            initialized = true;
            return null;
        });
    }

    boolean ready() throws ExecutionException {
        checkFailure();
        int buffered = batches.get();
        if (!initialized || buffered >= MAX_BUFFERED_BATCHES) return false;
        // The client may now own one capture batch in addition to all submitted batches.
        maxBufferedBatches = Math.max(maxBufferedBatches, buffered + 1);
        return true;
    }

    boolean finished() throws ExecutionException, InterruptedException {
        if (terminal == null || !terminal.isDone()) return false;
        terminal.get();
        checkFailure();
        return true;
    }

    void write(@NotNull List<Capture> captures, @NotNull List<Failure> failures) {
        batches.incrementAndGet();
        submit(() -> {
            long started = System.nanoTime();
            try {
                if (failure != null || publication.get() == Publication.CANCELED) return null;
                assemblyBatches++;
                IconPackWriter output = output();
                for (Failure omitted : failures) {
                    if (publication.get() == Publication.CANCELED) return null;
                    output.failure(omitted.context, omitted.reason);
                }
                for (Capture capture : captures) {
                    if (publication.get() == Publication.CANCELED) return null;
                    output.add(capture.key, capture.pixels);
                }
            } finally {
                assemblyWorkNanos += System.nanoTime() - started;
                // Release transferred images before making capacity available to the client.
                captures.clear();
                failures.clear();
                batches.decrementAndGet();
            }
            return null;
        });
    }

    void finish(@NotNull Map<String, Long> counts, @NotNull List<String> sources) {
        terminal = submit(() -> {
            if (writer == null) return null;
            try (IconPackWriter output = writer) {
                if (failure != null) return null;
                counts.put("assemblyWorkNanos", assemblyWorkNanos);
                counts.put("assemblyBatches", assemblyBatches);
                counts.put("maxBufferedBatches", (long) maxBufferedBatches);
                output.statistics(counts, sources);
                completed = output.finish(
                    () -> publication.get() == Publication.CANCELED,
                    () -> publication.compareAndSet(Publication.OPEN, Publication.COMMITTING));
                publication.set(Publication.COMPLETE);
            } catch (CancellationException exception) {
                if (exception.getSuppressed().length != 0) throw new IOException("Icon pack cleanup failed", exception);
                if (publication.get() != Publication.CANCELED) throw exception;
                // Cancellation is reported by the owning client session.
            } catch (IOException | RuntimeException | Error exception) {
                publication.compareAndSet(Publication.COMMITTING, Publication.OPEN);
                throw exception;
            }
            return null;
        });
    }

    boolean cancel() {
        if (!publication.compareAndSet(Publication.OPEN, Publication.CANCELED)) {
            return publication.get() == Publication.CANCELED;
        }
        if (!worker.isShutdown()) {
            terminal = submit(() -> {
                // This runs after every previously submitted batch and any finish attempt.
                if (writer != null) writer.close();
                return null;
            });
            worker.shutdown();
        }
        return true;
    }

    @Nullable
    Path completedPath() {
        return completed;
    }

    void close() {
        worker.shutdown();
    }

    private @NotNull Future<?> submit(@NotNull Callable<Void> task) {
        return worker.submit(() -> {
            try {
                task.call();
            } catch (Throwable exception) {
                if (exception instanceof CancellationException && exception.getSuppressed().length == 0
                    && publication.get() == Publication.CANCELED) return null;
                Throwable previous = failure;
                if (previous == null) failure = exception;
                else if (previous != exception) {
                    if (exception instanceof VirtualMachineError || exception instanceof ThreadDeath) {
                        exception.addSuppressed(previous);
                        failure = exception;
                    } else previous.addSuppressed(exception);
                }
            }
            return null;
        });
    }

    private void checkFailure() throws ExecutionException {
        Throwable problem = failure;
        if (problem != null) throw new ExecutionException(problem);
    }

    private @NotNull IconPackWriter output() throws IOException {
        if (writer == null) throw new IOException("Icon pack writer was not initialized");
        return writer;
    }

    static final class Capture {

        final StableKey key;
        final int[] pixels;

        Capture(@NotNull StableKey key, int @NotNull [] pixels) {
            this.key = key;
            this.pixels = pixels;
        }
    }

    static final class Failure {

        final String context;
        final String reason;

        Failure(@NotNull String context, @NotNull String reason) {
            this.context = context;
            this.reason = reason;
        }
    }
}
