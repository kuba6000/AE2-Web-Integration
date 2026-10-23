package pl.kuba6000.ae2webintegration.core.icons.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * One assembly owner consumes detached batches. All control calls belong to one producer thread;
 * readiness and completion polls never wait for unfinished I/O.
 */
public final class PackExportWriter {

    public static final int MAX_CAPTURES_PER_BATCH = 1024;
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
    private final Set<StableKey> disabledAnimations = new HashSet<>();
    private volatile boolean animationsStopped;

    /** Takes ownership of the detached metadata source and invokes it once on the assembly worker. */
    public PackExportWriter(@NotNull Path root, @NotNull IMetadataSource environment) {
        try {
            submit(() -> {
                writer = new IconPackWriter(
                    root,
                    environment.create(() -> publication.get() == Publication.CANCELED),
                    PAGE_SIZE);
                initialized = true;
                return null;
            });
        } catch (RuntimeException | Error failure) {
            worker.shutdown();
            throw failure;
        }
    }

    /** Authorizes at most one batch, including its capture time. Repeated polls do not reserve more slots. */
    public boolean ready() throws ExecutionException {
        checkFailure();
        if (terminal != null) return false;
        int buffered = batches.get();
        if (!initialized || buffered >= MAX_BUFFERED_BATCHES) return false;
        // The client may now own one capture batch in addition to all submitted batches.
        maxBufferedBatches = Math.max(maxBufferedBatches, buffered + 1);
        return true;
    }

    /** Reports retained failures only after terminal cleanup has settled. */
    public boolean finished() throws ExecutionException, InterruptedException {
        if (terminal == null || !terminal.isDone()) return false;
        terminal.get();
        checkFailure();
        return true;
    }

    /**
     * Transfers lists and pixel arrays after a successful ready poll. Nonempty lists must support clear;
     * neither lists nor arrays may be reused by the producer. Captures must respect MAX_CAPTURES_PER_BATCH.
     */
    public void write(@NotNull List<Capture> captures, @NotNull List<Failure> failures) {
        requireAccepting();
        batches.incrementAndGet();
        submit(() -> {
            long started = System.nanoTime();
            try {
                writeBatch(captures, failures);
            } finally {
                assemblyWorkNanos += System.nanoTime() - started;
                // Release transferred images before making capacity available to the client.
                if (!captures.isEmpty()) captures.clear();
                if (!failures.isEmpty()) failures.clear();
                batches.decrementAndGet();
            }
            return null;
        });
    }

    /** Queue only after draining and transferring the last static batch. */
    public void beginAnimations() {
        requireAccepting();
        submit(() -> {
            if (failure == null && publication.get() != Publication.CANCELED) output().sealStatic();
            return null;
        });
    }

    /** Global optional budget exhaustion; the producer should stop submitting further native animation captures. */
    public boolean animationsStopped() {
        return animationsStopped;
    }

    private void writeBatch(@NotNull List<Capture> captures, @NotNull List<Failure> failures) throws IOException {
        if (failure != null || publication.get() == Publication.CANCELED) return;
        assemblyBatches++;
        // Borrowed for this batch; finish/cancel closes the single assembly owner.
        // noinspection resource
        IconPackWriter output = output();
        for (Failure omitted : failures) {
            if (publication.get() == Publication.CANCELED) return;
            if (omitted.animationKey() != null) {
                disabledAnimations.add(omitted.animationKey());
                output.discardAnimation(omitted.animationKey());
            }
            output.failure(omitted.context(), omitted.reason());
        }
        for (Capture capture : captures) {
            if (publication.get() == Publication.CANCELED) return;
            if (capture.kind() == CaptureKind.STATIC) output.add(capture.key(), capture.pixels());
            else if (!animationsStopped && !disabledAnimations.contains(capture.key())) {
                try {
                    if (capture.kind() == CaptureKind.FRAME)
                        output.addAnimationFrame(capture.key(), capture.pixels(), Math.toIntExact(capture.timing()));
                    else output.addAnimationSample(capture.key(), capture.pixels(), capture.timing());
                } catch (IconPackWriter.CapacityException limit) {
                    if (limit.scope() == IconPackWriter.CapacityException.Scope.EXPORT) animationsStopped = true;
                    disabledAnimations.add(capture.key());
                    output.discardAnimation(capture.key());
                    output.failure(capture.key() + "/animation", limit.getMessage());
                }
            }
        }
    }

    /** Closes input and transfers a mutable statistics map and a read-only source list. */
    public void finish(@NotNull Map<String, Long> counts, @NotNull List<String> sources) {
        requireAccepting();
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

    /** Stops input and schedules cleanup. Repeated cancellation is harmless; publication can refuse it. */
    public boolean cancel() {
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

    /** Only available after finished reports successful publication. */
    public @Nullable Path completedPath() {
        return completed;
    }

    /** Releases the executor after terminal settlement; active callers must cancel and keep polling first. */
    public void close() {
        if (terminal == null || !terminal.isDone()) throw new IllegalStateException("Export cleanup has not settled");
        worker.shutdown();
    }

    private void requireAccepting() {
        if (terminal != null) throw new IllegalStateException("Export input is closed");
    }

    private @NotNull Future<?> submit(@NotNull Callable<Void> task) {
        return worker.submit(() -> {
            try {
                task.call();
            } catch (Throwable exception) {
                recordFailure(exception);
            }
            return null;
        });
    }

    private void recordFailure(@NotNull Throwable exception) {
        if (exception instanceof CancellationException && exception.getSuppressed().length == 0
            && publication.get() == Publication.CANCELED) return;
        Throwable previous = failure;
        if (previous == null) {
            failure = exception;
            return;
        }
        if (previous == exception) return;
        if (exception instanceof VirtualMachineError || exception instanceof ThreadDeath) {
            exception.addSuppressed(previous);
            failure = exception;
        } else {
            previous.addSuppressed(exception);
        }
    }

    private void checkFailure() throws ExecutionException {
        Throwable problem = failure;
        if (problem != null) throw new ExecutionException(problem);
    }

    private @NotNull IconPackWriter output() throws IOException {
        if (writer == null) throw new IOException("Icon pack writer was not initialized");
        return writer;
    }

    public enum CaptureKind {
        STATIC,
        FRAME,
        SAMPLE
    }

    /** Timing is milliseconds for FRAME and draw-submission nanoTime for SAMPLE. STATIC ignores timing. */
    @Desugar
    public record Capture(@NotNull StableKey key, int @NotNull [] pixels, @NotNull CaptureKind kind, long timing) {

        public Capture(@NotNull StableKey key, int @NotNull [] pixels) {
            this(key, pixels, CaptureKind.STATIC, 0);
        }

        public static @NotNull Capture frame(@NotNull StableKey key, int @NotNull [] pixels, int durationMillis) {
            return new Capture(key, pixels, CaptureKind.FRAME, durationMillis);
        }

        public static @NotNull Capture sample(@NotNull StableKey key, int @NotNull [] pixels, long submittedNanos) {
            return new Capture(key, pixels, CaptureKind.SAMPLE, submittedNanos);
        }
    }

    @Desugar
    public record Failure(@NotNull String context, @NotNull String reason, @Nullable StableKey animationKey) {

        public Failure(@NotNull String context, @NotNull String reason) {
            this(context, reason, null);
        }

        public static @NotNull Failure animation(@NotNull StableKey key, @NotNull String context,
            @NotNull String reason) {
            return new Failure(context, reason, key);
        }
    }
}
