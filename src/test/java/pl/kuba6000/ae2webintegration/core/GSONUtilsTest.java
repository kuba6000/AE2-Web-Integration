package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

class GSONUtilsTest {

    @TempDir
    Path directory;

    @Test
    @SuppressWarnings("ReadWriteStringCanBeUsed") // Files.readString requires Java 11; tests also target Java 8.
    void createsParentDirectoriesAndReplacesCompleteJson() throws Exception {
        Path target = directory.resolve("nested/data.json");
        GSONUtils.writeAtomically(target.toFile(), Collections.singletonMap("name", "first"));
        GSONUtils.writeAtomically(target.toFile(), Collections.singletonMap("name", "żółw"));

        JsonObject saved = GSONUtils.GSON_BUILDER.create()
            .fromJson(new String(Files.readAllBytes(target), StandardCharsets.UTF_8), JsonObject.class);
        assertEquals(
            "żółw",
            saved.get("name")
                .getAsString());
    }

    @Test
    void serializationFailurePreservesTheExistingFile() throws Exception {
        Path target = directory.resolve("data.json");
        byte[] previous = "{\"name\":\"original\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(target, previous);

        assertThrows(
            IllegalArgumentException.class,
            () -> GSONUtils.writeAtomically(target.toFile(), Collections.singletonMap("value", Double.NaN)));

        assertArrayEquals(previous, Files.readAllBytes(target));
    }
}
