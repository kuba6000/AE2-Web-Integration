package pl.kuba6000.ae2webintegration.core.utils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Deletes temporary trees without following symbolic links, retrying transient filesystem failures. */
public final class TempDirectories {

    private static final int ATTEMPTS = 20;

    private TempDirectories() {}

    /** Call only after owned readers and writers have closed their handles. Missing paths are harmless. */
    public static void deleteRecursively(@NotNull Path root) throws IOException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            IOException failure = deleteOnce(root);
            if (failure == null) return;
            if (!(failure instanceof FileSystemException) || attempt == ATTEMPTS - 1) throw failure;
            // Windows can retain a directory briefly after its children have been deleted.
            try {
                Thread.sleep(25L * (attempt + 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                InterruptedIOException cleanup = new InterruptedIOException(
                    "Interrupted while deleting temporary directory " + root);
                cleanup.initCause(interrupted);
                cleanup.addSuppressed(failure);
                throw cleanup;
            }
        }
    }

    private static @Nullable IOException deleteOnce(@NotNull Path root) {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return null;
        List<Path> paths;
        // Close traversal handles before deleting directories, especially on Windows.
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        } catch (UncheckedIOException exception) {
            return exception.getCause();
        } catch (IOException exception) {
            return exception;
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                return exception;
            }
        }
        return null;
    }
}
