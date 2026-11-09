package pl.kuba6000.ae2webintegration.core.themes;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Validated server theme archives; their handles belong to one server lifecycle. */
public final class ThemeCatalog implements Closeable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final int MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 64 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_PATH_LENGTH = 240;
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_VERSION_LENGTH = 64;
    private static final int BUFFER_BYTES = 8192;
    private static final Set<String> VIEWS = new HashSet<>(
        Arrays.asList(
            "home",
            "web-settings",
            "server-settings",
            "about",
            "items",
            "settings",
            "cpus",
            "history",
            "plan",
            "tracking"));

    /**
     * Installed theme and its entry points.
     *
     * @param id                  theme identifier used in asset URLs
     * @param name                display name
     * @param version             theme version
     * @param supportedModVersion mod version family supported by the theme
     * @param views               views implemented by the theme
     * @param entry               JavaScript entry path relative to the theme's asset URL
     * @param styles              stylesheet paths relative to the theme's asset URL
     */
    @Desugar
    public record Manifest(@NotNull String id, @NotNull String name, @NotNull String version,
        @NotNull String supportedModVersion, @NotNull List<String> views, @NotNull String entry,
        @NotNull List<String> styles) {}

    /**
     * Available themes and the server's default choice.
     *
     * @param defaultTheme default theme identifier
     * @param themes       installed themes
     */
    @Desugar
    public record Description(@NotNull String defaultTheme, @NotNull List<Manifest> themes) {}

    private final Map<String, Pack> packs = new LinkedHashMap<>();

    /** Invalid packages never prevent the web interface or other installed themes from starting. */
    public static @NotNull ThemeCatalog load(@NotNull Path directory) {
        ThemeCatalog catalog = new ThemeCatalog();
        Set<String> ambiguous = new HashSet<>();
        try {
            Files.createDirectories(directory);
            try (Stream<Path> files = Files.list(directory)) {
                for (Path path : (Iterable<Path>) files.sorted()::iterator) {
                    if (!Files.isRegularFile(path) || !path.getFileName()
                        .toString()
                        .endsWith(".zip")) continue;
                    try {
                        Pack pack = Pack.open(path);
                        String id = pack.manifest.id();
                        Pack previous = catalog.packs.remove(id);
                        if (previous != null) {
                            ambiguous.add(id);
                            try {
                                previous.close();
                            } finally {
                                pack.close();
                            }
                            LOG.warn("Ignoring duplicate server theme id {} in {}", id, path);
                            continue;
                        }
                        if (ambiguous.contains(id)) {
                            pack.close();
                            LOG.warn("Ignoring duplicate server theme id {} in {}", id, path);
                        } else {
                            catalog.packs.put(id, pack);
                        }
                    } catch (IOException | IllegalArgumentException invalid) {
                        LOG.warn("Cannot load server theme {}", path, invalid);
                    }
                }
            }
        } catch (IOException failure) {
            LOG.warn("Cannot read server theme directory {}", directory, failure);
        }
        return catalog;
    }

    public synchronized @NotNull Description describe(@NotNull String configuredDefault) {
        String selected = "builtin:default";
        if (configuredDefault.startsWith("server:")
            && packs.containsKey(configuredDefault.substring("server:".length()))) {
            selected = configuredDefault;
        }
        List<Manifest> manifests = new ArrayList<>();
        for (Pack pack : packs.values()) manifests.add(pack.manifest);
        return new Description(selected, Collections.unmodifiableList(manifests));
    }

    /** Materializes one bounded asset while holding the close lock, never during a slow HTTP write. */
    public synchronized byte @Nullable [] read(@NotNull String id, @NotNull String path) throws IOException {
        Pack pack = packs.get(id);
        if (pack == null || !validPath(path)) return null;
        ZipEntry entry = pack.files.get(path);
        if (entry == null) return null;
        try (InputStream input = pack.zip.getInputStream(entry)) {
            return readBounded(input, MAX_FILE_BYTES);
        }
    }

    @Override
    public synchronized void close() {
        for (Pack pack : packs.values()) {
            try {
                pack.close();
            } catch (IOException failure) {
                LOG.warn("Cannot close server theme {}", pack.manifest.id(), failure);
            }
        }
        packs.clear();
    }

    private static boolean validPath(String path) {
        if (path.isEmpty() || path.length() > MAX_PATH_LENGTH || !path.matches("[A-Za-z0-9._/-]+")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - bytes.size()) throw new IOException("Theme file exceeds size limit");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static Manifest manifest(byte[] bytes, Map<String, ZipEntry> files) throws IOException {
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            JsonObject object = readManifest(json);
            String id = string(object.get("id"), MAX_ID_LENGTH);
            if (!id.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IOException("Invalid theme id");
            String name = string(object.get("name"), MAX_NAME_LENGTH);
            String version = string(object.get("version"), MAX_VERSION_LENGTH);
            String supported = string(object.get("supportedModVersion"), MAX_VERSION_LENGTH);
            if (!supported.matches("[0-9]+\\.[0-9]+\\.x")) throw new IOException("Invalid supported mod version");
            List<String> views = strings(object.get("views"), MAX_NAME_LENGTH);
            if (views.isEmpty() || !VIEWS.containsAll(views)) throw new IOException("Invalid theme views");
            String entry = asset(string(object.get("entry"), MAX_PATH_LENGTH), ".js", files);
            List<String> styles = strings(object.get("styles"), MAX_PATH_LENGTH);
            for (String style : styles) asset(style, ".css", files);
            return new Manifest(id, name, version, supported, views, entry, styles);
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            throw new IOException("Invalid theme manifest", invalid);
        }
    }

    /** Gson's tree parser enables lenient syntax; package manifests use the same strict JSON as browsers. */
    private static JsonObject readManifest(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            JsonObject object = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                switch (field) {
                    case "id", "name", "version", "supportedModVersion", "entry" -> {
                        if (reader.peek() != JsonToken.STRING) throw new IOException("Expected manifest string");
                        object.addProperty(field, reader.nextString());
                    }
                    case "views", "styles" -> {
                        JsonArray values = new JsonArray();
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (reader.peek() != JsonToken.STRING)
                                throw new IOException("Expected manifest string array");
                            values.add(new JsonPrimitive(reader.nextString()));
                        }
                        reader.endArray();
                        object.add(field, values);
                    }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing manifest data");
            return object;
        }
    }

    private static String string(@Nullable JsonElement value, int maxLength) throws IOException {
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isString()) {
            throw new IOException("Expected manifest string");
        }
        String text = value.getAsString();
        if (text.trim()
            .isEmpty() || text.length() > maxLength) throw new IOException("Invalid manifest string length");
        return text;
    }

    private static List<String> strings(@Nullable JsonElement value, int maxLength) throws IOException {
        if (value == null || !value.isJsonArray()) throw new IOException("Expected manifest array");
        JsonArray array = value.getAsJsonArray();
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) {
            String text = string(element, maxLength);
            if (result.contains(text)) throw new IOException("Duplicate manifest array entry");
            result.add(text);
        }
        return Collections.unmodifiableList(result);
    }

    private static String asset(String path, String extension, Map<String, ZipEntry> files) throws IOException {
        if (!validPath(path) || !path.endsWith(extension) || !files.containsKey(path)) {
            throw new IOException("Missing or invalid theme asset: " + path);
        }
        return path;
    }

    private static final class Pack implements Closeable {

        private final ZipFile zip;
        private final Map<String, ZipEntry> files;
        private final Manifest manifest;

        private Pack(ZipFile zip, Map<String, ZipEntry> files, Manifest manifest) {
            this.zip = zip;
            this.files = files;
            this.manifest = manifest;
        }

        private static Pack open(Path path) throws IOException {
            if (Files.size(path) > MAX_ARCHIVE_BYTES) throw new IOException("Theme ZIP exceeds size limit");
            ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
            try {
                Map<String, ZipEntry> files = new LinkedHashMap<>();
                Set<String> names = new HashSet<>();
                long expanded = 0;
                int entries = 0;
                Enumeration<? extends ZipEntry> enumeration = zip.entries();
                byte[] buffer = new byte[BUFFER_BYTES];
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
                    if (++entries > MAX_ENTRIES || !validPath(name) || !names.add(name)) {
                        throw new IOException("Invalid or duplicate theme ZIP path");
                    }
                    if (entry.isDirectory()) {
                        try (InputStream input = zip.getInputStream(entry)) {
                            if (input.read() != -1) throw new IOException("Theme ZIP directory contains file data");
                        }
                        continue;
                    }
                    int limit = name.equals("theme.json") ? MAX_MANIFEST_BYTES : MAX_FILE_BYTES;
                    long size = 0;
                    try (InputStream input = zip.getInputStream(entry)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            size += read;
                            expanded += read;
                            if (size > limit || expanded > MAX_EXPANDED_BYTES) {
                                throw new IOException("Expanded theme exceeds size limit");
                            }
                        }
                    }
                    files.put(name, entry);
                }
                ZipEntry manifest = files.get("theme.json");
                if (manifest == null) throw new IOException("Missing theme.json");
                try (InputStream input = zip.getInputStream(manifest)) {
                    return new Pack(zip, files, manifest(readBounded(input, MAX_MANIFEST_BYTES), files));
                }
            } catch (IOException | RuntimeException failure) {
                zip.close();
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }
}
