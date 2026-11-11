package pl.kuba6000.ae2webintegration.core.utils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.util.function.Consumer;

/** Writes a complete temporary file before replacing the previous configuration or data file. */
public final class AtomicFileWriter {

    private AtomicFileWriter() {}

    public static void write(File target, Consumer<Writer> serializer) throws IOException {
        File directory = target.getParentFile();
        if (directory != null) {
            Files.createDirectories(directory.toPath());
        }
        File temporary = new File(target.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary);
                Writer writer = new OutputStreamWriter(out, UTF_8)) {
                serializer.accept(writer);
                writer.flush();
                out.getFD()
                    .sync();
            }
            moveIntoPlace(temporary, target);
        } finally {
            // Nothing useful to do if this fails; the stale temp file is harmless.
            // noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private static void moveIntoPlace(File temporary, File target) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                try {
                    Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException busy) {
                // Windows often keeps the replacement file open for a moment after the writer closes.
                lastFailure = busy;
                try {
                    Thread.sleep(25L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    throw busy;
                }
            }
        }
        throw lastFailure;
    }
}
