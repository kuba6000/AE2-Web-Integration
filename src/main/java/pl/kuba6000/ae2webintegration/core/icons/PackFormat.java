package pl.kuba6000.ae2webintegration.core.icons;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

final class PackFormat {

    static final int VERSION = 2;
    static final int ICON_SIZE = 64;
    static final int ICON_PIXELS = ICON_SIZE * ICON_SIZE;
    static final int ICON_BYTES = ICON_PIXELS * Integer.BYTES;
    static final int GUTTER = 1;
    static final int CELL_PITCH = ICON_SIZE + 2 * GUTTER;
    static final int SMALL_PAGE_DIMENSION = 512;
    static final int DEFAULT_PAGE_DIMENSION = 1024;
    static final int MAX_PAGE_DIMENSION = 2048;
    static final int MAX_FAILURE_RECORDS = 100;
    static final int MAX_FAILURE_TEXT = 1024;
    static final int MAX_STATISTICS = 64;
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final int MAX_JSON_DEPTH = 16;
    private static final int MAX_TEXT_LENGTH = 4096;
    private static final @NotNull Pattern NONNEGATIVE_INTEGER = Pattern.compile("0|[1-9][0-9]*");
    static final int MAX_KEYS = 250_000;
    static final int MAX_MANIFEST = 64 * 1024 * 1024;
    static final int MAX_PAGE = 16 * 1024 * 1024;
    static final long MAX_ARCHIVE = 1024L * 1024 * 1024;
    static final @NotNull Gson GSON = new GsonBuilder().disableHtmlEscaping()
        .create();

    private PackFormat() {}

    private static @NotNull MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static @NotNull String hash(byte @NotNull [] bytes) {
        return hex(sha256().digest(bytes));
    }

    @SuppressWarnings("PMD.AvoidMagicNumbers") // Byte-to-hex nibble masks and shifts are the encoding itself.
    private static @NotNull String hex(byte @NotNull [] bytes) {
        char[] chars = new char[bytes.length * 2];
        String alphabet = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = alphabet.charAt((bytes[i] & 255) >>> 4);
            chars[i * 2 + 1] = alphabet.charAt(bytes[i] & 15);
        }
        return new String(chars);
    }

    static byte @NotNull [] json(@NotNull Object object) {
        return GSON.toJson(object)
            .getBytes(StandardCharsets.UTF_8);
    }

    static byte @NotNull [] read(@NotNull InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maximum) throw new IOException("Pack entry exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static @NotNull JsonObject parse(byte @NotNull [] bytes) throws IOException {
        String json = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
        rejectNegativeZero(json);
        // Gson normally keeps the last duplicate field. Audit structure before ordinary tree decoding.
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            audit(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing manifest data");
        }
        try {
            return new JsonParser().parse(json)
                .getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid manifest", e);
        }
    }

    private static void rejectNegativeZero(@NotNull String json) throws IOException {
        // Gson 2.2.4 normalizes the numeric token -0 to 0, losing the sign before integer validation.
        // Only protect that lexical distinction here; JsonReader still validates the JSON grammar.
        boolean quoted = false;
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (quoted) {
                if (current == '\\') index++;
                else if (current == '"') quoted = false;
            } else if (current == '"') quoted = true;
            else if (current == '-' && index + 1 < json.length()
                && json.charAt(index + 1) == '0'
                && (index == 0 || " \t\r\n[:,".indexOf(json.charAt(index - 1)) >= 0)
                && (index + 2 == json.length() || " \t\r\n,]}".indexOf(json.charAt(index + 2)) >= 0)) {
                    throw new IOException("Expected nonnegative integer");
                }
        }
    }

    private static void audit(@NotNull JsonReader reader, int depth) throws IOException {
        if (depth > MAX_JSON_DEPTH) throw new IOException("Manifest nesting too deep");
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            Set<String> names = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                if (!names.add(reader.nextName())) throw new IOException("Duplicate manifest field");
                audit(reader, depth + 1);
            }
            reader.endObject();
        } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            while (reader.hasNext()) audit(reader, depth + 1);
            reader.endArray();
        } else reader.skipValue();
    }

    private static @NotNull Object canonical(@NotNull JsonElement value) {
        if (value.isJsonObject()) {
            Map<String, Object> result = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject()
                .entrySet()) result.put(entry.getKey(), canonical(entry.getValue()));
            return result;
        }
        if (value.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) result.add(canonical(item));
            return result;
        }
        return value;
    }

    static @NotNull String packId(@NotNull JsonObject manifest) {
        JsonObject projection = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : manifest.entrySet()) {
            if (!entry.getKey()
                .equals("packId")
                && !entry.getKey()
                    .equals("generatedAt")
                && !entry.getKey()
                    .equals("failures")
                && !entry.getKey()
                    .equals("statistics"))
                projection.add(entry.getKey(), entry.getValue());
        }
        return hash(json(canonical(projection)));
    }

    static int integer(@Nullable JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isNumber())
            throw new IOException("Expected nonnegative integer");
        String number = value.getAsString();
        if (!NONNEGATIVE_INTEGER.matcher(number)
            .matches()) throw new IOException("Expected nonnegative integer");
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            throw new IOException("Integer outside supported range", e);
        }
    }

    static @NotNull String text(@NotNull JsonObject object, @NotNull String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isString()
            || value.getAsString()
                .length() > MAX_TEXT_LENGTH)
            throw new IOException("Invalid manifest field: " + field);
        return value.getAsString();
    }

    static void digest(@NotNull String text) throws IOException {
        if (!text.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256 digest");
    }

    static void zipEntry(@NotNull ZipOutputStream zip, @NotNull String name, byte @NotNull [] bytes, boolean stored)
        throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
