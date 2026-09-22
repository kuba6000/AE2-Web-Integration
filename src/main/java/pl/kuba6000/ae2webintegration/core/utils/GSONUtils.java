package pl.kuba6000.ae2webintegration.core.utils;

import java.io.File;
import java.io.IOException;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

public class GSONUtils {

    public static final GsonBuilder GSON_BUILDER = new GsonBuilder()
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
