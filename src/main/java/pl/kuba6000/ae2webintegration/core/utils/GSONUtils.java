package pl.kuba6000.ae2webintegration.core.utils;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import pl.kuba6000.ae2webintegration.core.binding.BindingCatalog;
import pl.kuba6000.ae2webintegration.core.binding.BindingCatalogs;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

public class GSONUtils {

    private static final Map<Class<? extends Annotation>, Gson> PROJECTED_GSONS = new HashMap<>();
    public static final GsonBuilder GSON_BUILDER = createBuilder();

    /** Creates an independent builder with the shared API adapters. */
    public static GsonBuilder createBuilder() {
        return new GsonBuilder().registerTypeAdapterFactory(new TypeAdapterFactory() {

            @Override
            @SuppressWarnings("unchecked") // Catalog lookup uses the exact requested wrapper class.
            public <T> @Nullable TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                for (BindingCatalog catalog : BindingCatalogs.ALL) {
                    TypeAdapter<?> adapter = catalog.adapter(type.getRawType());
                    if (adapter != null) return (TypeAdapter<T>) adapter;
                }
                return null;
            }
        })
            // Old Gson otherwise reflects into java.lang.Void, which modern JVM modules forbid.
            .registerTypeAdapter(Void.class, new TypeAdapter<Void>() {

                @Override
                public void write(JsonWriter output, Void value) throws IOException {
                    output.nullValue();
                }

                @Override
                public Void read(JsonReader input) throws IOException {
                    input.nextNull();
                    return null;
                }
            }.nullSafe())
            .registerTypeAdapter(StableKey.class, new TypeAdapter<StableKey>() {

                @Override
                public void write(JsonWriter output, StableKey key) throws IOException {
                    output.value(key.toString());
                }

                @Override
                public StableKey read(JsonReader input) throws IOException {
                    if (input.peek() != JsonToken.STRING) throw new JsonParseException("Stable key must be a string");
                    try {
                        return StableKey.parse(input.nextString());
                    } catch (IllegalArgumentException e) {
                        throw new JsonParseException("Invalid stable key", e);
                    }
                }
            }.nullSafe())
            .serializeNulls();
    }

    /** Used by generated wrappers; shared adapters and recursive field exclusions apply to their value. */
    public static <T> @NotNull TypeAdapter<T> projectionAdapter(@NotNull Class<T> type,
        @NotNull Class<? extends Annotation> excluded) {
        Gson gson;
        synchronized (PROJECTED_GSONS) {
            gson = PROJECTED_GSONS.get(excluded);
            if (gson == null) {
                gson = createBuilder().setExclusionStrategies(new ExclusionStrategy() {

                    @Override
                    public boolean shouldSkipField(FieldAttributes field) {
                        return field.getAnnotation(excluded) != null;
                    }

                    @Override
                    public boolean shouldSkipClass(Class<?> type) {
                        return false;
                    }
                })
                    .create();
                PROJECTED_GSONS.put(excluded, gson);
            }
        }
        // Publish before resolving adapters: represented values can contain recursive wrappers.
        return gson.getAdapter(type);
    }

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
        AtomicFileWriter.write(
            target,
            writer -> GSON_BUILDER.create()
                .toJson(value, writer));
    }
}
