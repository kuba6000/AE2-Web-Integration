package pl.kuba6000.ae2webintegration.core.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;

public class GSONUtils {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface SkipGSON {}

    private static final ExclusionStrategy GSONStrategy = new ExclusionStrategy() {

        @Override
        public boolean shouldSkipField(FieldAttributes f) {
            return f.getAnnotation(SkipGSON.class) != null;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };

    public static final GsonBuilder GSON_BUILDER = new GsonBuilder().addSerializationExclusionStrategy(GSONStrategy)
        .addDeserializationExclusionStrategy(GSONStrategy)
        .serializeNulls();

    /**
     * Serializes to a sibling temporary file, forces it to disk, then renames it over the target.
     * <p>
     * Writing straight to the target truncates it first, so an interruption anywhere in the middle leaves
     * a half-written file behind - and for the account store that means every password gone. An atomic
     * rename preserves the old or complete new file. When the filesystem does not support it, the
     * fallback replaces the file without an atomicity guarantee.
     *
     * @throws IOException if writing or moving fails. Failures before the move leave the target untouched;
     *                     a failed non-atomic replacement may affect the target.
     */
    public static void writeAtomically(File target, Object value) throws IOException {
        File directory = target.getParentFile();
        if (directory != null) {
            Files.createDirectories(directory.toPath());
        }
        File temporary = new File(target.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary);
                Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                GSON_BUILDER.create()
                    .toJson(value, writer);
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
