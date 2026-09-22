package pl.kuba6000.ae2webintegration.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Deletes a directory tree after the walk has released its directory handles.
 * <p>
 * On Windows, removing a file and then its directory in the same breath often fails with
 * {@link DirectoryNotEmptyException} even though the directory listing is already empty. A short
 * retry covers that delay.
 */
public final class TempDirectories {

    private static final int ATTEMPTS = 20;

    private TempDirectories() {}

    public static void deleteRecursively(Path root) {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            lastFailure = deleteOnce(root);
            if (lastFailure == null) {
                return;
            }
            if (!shouldRetry(lastFailure)) {
                break;
            }
            try {
                Thread.sleep(25L * (attempt + 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                throw new UncheckedIOException(lastFailure);
            }
        }
        throw new UncheckedIOException(lastFailure);
    }

    private static IOException deleteOnce(Path root) {
        if (Files.notExists(root)) {
            return null;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder())
                .collect(Collectors.<Path>toList());
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

    private static boolean shouldRetry(IOException failure) {
        return failure instanceof DirectoryNotEmptyException || failure instanceof FileSystemException;
    }
}
