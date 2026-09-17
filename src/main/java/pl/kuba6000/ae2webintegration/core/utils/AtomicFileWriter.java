package pl.kuba6000.ae2webintegration.core.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
                Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                serializer.accept(writer);
                writer.flush();
                out.getFD()
                    .sync();
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Nothing useful to do if this fails; the stale temp file is harmless.
            // noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }
}
