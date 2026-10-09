package pl.kuba6000.ae2webintegration.core.icons.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Deterministic fingerprints for filesystem-backed resource packs; callers retain pack priority order. */
public final class ResourcePackFingerprint {

    private static final int HASH_BUFFER_SIZE = 65536;

    private ResourcePackFingerprint() {}

    /** Hashes file bytes, or sorted slash-normalized relative paths and file hashes for a directory. */
    public static @NotNull HashCode hash(@NotNull Path pack, @NotNull BooleanSupplier canceled) throws IOException {
        if (!Files.isDirectory(pack)) return hashFile(pack, canceled);
        List<Path> entries;
        try (Stream<Path> paths = Files.walk(pack)) {
            entries = paths.filter(path -> {
                checkCanceled(canceled);
                return Files.isRegularFile(path);
            })
                .sorted(
                    Comparator.comparing(
                        path -> pack.relativize(path)
                            .toString()
                            .replace('\\', '/')))
                .collect(Collectors.toList());
        }
        Hasher hash = Hashing.sha256()
            .newHasher();
        for (Path entry : entries) {
            StableKey.writeText(
                hash,
                pack.relativize(entry)
                    .toString()
                    .replace('\\', '/'));
            hash.putBytes(hashFile(entry, canceled).asBytes());
        }
        return hash.hash();
    }

    private static @NotNull HashCode hashFile(@NotNull Path file, @NotNull BooleanSupplier canceled)
        throws IOException {
        checkCanceled(canceled);
        Hasher hash = Hashing.sha256()
            .newHasher();
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCanceled(canceled);
                if (Thread.currentThread()
                    .isInterrupted()) throw new InterruptedIOException("Export interrupted");
                hash.putBytes(buffer, 0, read);
            }
        }
        return hash.hash();
    }

    private static void checkCanceled(@NotNull BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) throw new CancellationException("Icon export canceled");
    }

}
