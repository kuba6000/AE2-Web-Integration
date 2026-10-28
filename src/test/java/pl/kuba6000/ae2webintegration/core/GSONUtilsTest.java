package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.json.JsonProjection;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

class GSONUtilsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface ProtectedField {}

    @Desugar
    @JsonProjection(value = ProjectionValue.class, excludeFieldsAnnotatedWith = ProtectedField.class)
    record Projection(ProjectionValue value) {}

    static final class ProjectionValue {

        StableKey key = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        Nested nested = new Nested();
        @ProtectedField
        String secret = "default-root";
        Projection next;
    }

    static final class Nested {

        int count = 7;
        @ProtectedField
        String secret = "default-nested";
    }

    @Test
    void inputShapeProjectsBothDirectionsAndPreservesSharedAdapters() {
        Gson gson = GSONUtils.createBuilder()
            .create();
        ProjectionValue value = new ProjectionValue();
        JsonObject json = gson.toJsonTree(new Projection(value))
            .getAsJsonObject();
        assertEquals(
            value.key.toString(),
            json.get("key")
                .getAsString());
        assertFalse(json.has("secret"));
        assertFalse(
            json.getAsJsonObject("nested")
                .has("secret"));
        assertEquals(
            7,
            json.getAsJsonObject("nested")
                .get("count")
                .getAsInt());
        Projection decoded = gson.fromJson(
            "{\"key\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"secret\":\"bad\",\"nested\":{\"count\":9,\"secret\":\"bad\"}}",
            Projection.class);
        assertEquals(value.key, decoded.value().key);
        assertEquals(9, decoded.value().nested.count);
        assertEquals("default-root", decoded.value().secret);
        assertEquals("default-nested", decoded.value().nested.secret);
        JsonObject ordinary = gson.toJsonTree(value)
            .getAsJsonObject();
        assertEquals(
            "default-root",
            ordinary.get("secret")
                .getAsString());
        assertEquals(
            "default-nested",
            ordinary.getAsJsonObject("nested")
                .get("secret")
                .getAsString());
    }

    @Test
    void inputShapeSupportsNullAndRecursiveWrappedValues() {
        Gson gson = GSONUtils.createBuilder()
            .create();
        assertEquals("null", gson.toJson(null, Projection.class));
        assertNull(gson.fromJson("null", Projection.class));
        ProjectionValue value = new ProjectionValue();
        value.next = new Projection(new ProjectionValue());
        Projection decoded = gson.fromJson(gson.toJson(new Projection(value)), Projection.class);
        assertEquals(value.key, decoded.value().next.value().key);
        assertNull(decoded.value().next.value().next);
    }

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
