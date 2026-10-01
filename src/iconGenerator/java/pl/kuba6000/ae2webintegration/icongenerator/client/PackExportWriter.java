package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** One bounded batch of detached images is handed to this worker at a time. */
final class PackExportWriter {

    private static final int PAGE_SIZE = 1024;
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
    private Future<?> pending;
    private @Nullable IconPackWriter writer;
    private @Nullable Path completed;

    PackExportWriter(@NotNull Path root, @NotNull ExportEnvironment environment) {
        pending = worker.submit(() -> {
            writer = new IconPackWriter(
                root,
                environment.metadata(() -> publication.get() == Publication.CANCELED),
                PAGE_SIZE);
            return null;
        });
    }

    boolean ready() throws ExecutionException, InterruptedException {
        if (!pending.isDone()) return false;
        pending.get();
        return true;
    }

    void write(@NotNull List<Capture> captures, @NotNull List<Failure> failures) {
        pending = worker.submit(() -> {
            IconPackWriter output = output();
            for (Failure failure : failures) {
                if (publication.get() == Publication.CANCELED) return null;
                output.failure(failure.context, failure.reason);
            }
            for (Capture capture : captures) {
                if (publication.get() == Publication.CANCELED) return null;
                output.add(capture.key, capture.pixels);
            }
            return null;
        });
    }

    void finish(@NotNull Map<String, Long> counts, @NotNull List<String> sources) {
        pending = worker.submit(() -> {
            try (IconPackWriter output = output()) {
                output.statistics(counts, sources);
                completed = output.finish(
                    () -> publication.get() == Publication.CANCELED,
                    () -> publication.compareAndSet(Publication.OPEN, Publication.COMMITTING));
                publication.set(Publication.COMPLETE);
            } catch (CancellationException exception) {
                if (exception.getSuppressed().length != 0) throw new IOException("Icon pack cleanup failed", exception);
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
            Future<?> previous = pending;
            pending = worker.submit(() -> {
                try {
                    previous.get();
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (!(cause instanceof CancellationException) || cause.getSuppressed().length != 0) {
                        throw exception;
                    }
                } finally {
                    if (writer != null) writer.close();
                }
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
