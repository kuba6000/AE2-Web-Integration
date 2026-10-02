package pl.kuba6000.ae2webintegration.core.icons;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Validated immutable archive index. Close after all page readers have finished. */
public final class IconPack implements Closeable {

    public static final int CONTENT_SIZE = PackFormat.ICON_SIZE;

    private static final long PNG_SIGNATURE = 0x89504e470d0a1a0aL;
    private static final int PNG_IHDR_TYPE = 0x49484452;
    private static final int PNG_IHDR_DATA_LENGTH = 13;
    private static final int PNG_IHDR_TYPE_OFFSET = Long.BYTES + Integer.BYTES;
    private static final int PNG_IHDR_CRC_INPUT_LENGTH = Integer.BYTES + PNG_IHDR_DATA_LENGTH;
    private static final int PNG_IHDR_CRC_OFFSET = PNG_IHDR_TYPE_OFFSET + PNG_IHDR_CRC_INPUT_LENGTH;
    private static final int PNG_HEADER_LENGTH = PNG_IHDR_CRC_OFFSET + Integer.BYTES;
    private static final int LOCATION_FIELDS = 3;
    private final @NotNull ZipFile zip;
    private final @NotNull Metadata metadata;
    private final @NotNull String packId;
    private final @NotNull List<Page> pages;
    private final @NotNull Map<StableKey, Location> entries;
    private final int failures;
    private int readers;
    private boolean closing;

    private IconPack(@NotNull ZipFile zip, @NotNull Metadata metadata, @NotNull String packId,
        @NotNull List<Page> pages, @NotNull Map<StableKey, Location> entries, int failures) {
        this.zip = zip;
        this.metadata = metadata;
        this.packId = packId;
        this.pages = Collections.unmodifiableList(pages);
        this.entries = entries;
        this.failures = failures;
    }

    public static @NotNull IconPack open(@NotNull Path path, @NotNull String minecraftVersion, @NotNull String loader,
        @NotNull String identityContract, @NotNull String basePolicy) throws IOException {
        if (Files.size(path) > PackFormat.MAX_ARCHIVE) throw new IOException("Icon archive exceeds size limit");
        ZipFile zip = new ZipFile(path.toFile());
        try {
            return load(zip, minecraftVersion, loader, identityContract, basePolicy);
        } catch (IOException | RuntimeException | Error e) {
            try {
                zip.close();
            } catch (IOException close) {
                e.addSuppressed(close);
            }
            if (e instanceof IOException) throw (IOException) e;
            if (e instanceof Error) throw (Error) e;
            throw new IOException("Invalid icon pack", e);
        }
    }

    private static @NotNull IconPack load(@NotNull ZipFile zip, @NotNull String minecraftVersion,
        @NotNull String loader, @NotNull String identityContract, @NotNull String basePolicy) throws IOException {
        int archiveEntries = validateArchiveEntries(zip);
        JsonObject root = PackFormat.parse(bytes(zip, "manifest.json", PackFormat.MAX_MANIFEST));
        if (PackFormat.integer(root.get("formatVersion")) != PackFormat.VERSION
            || PackFormat.integer(root.get("contentWidth")) != PackFormat.ICON_SIZE
            || PackFormat.integer(root.get("contentHeight")) != PackFormat.ICON_SIZE)
            throw new IOException("Unsupported icon format");
        Metadata metadata = metadata(root);
        if (!minecraftVersion.equals(metadata.minecraftVersion) || !loader.equals(metadata.loader)
            || !identityContract.equals(metadata.identityContract)
            || !basePolicy.equals(metadata.basePolicy))
            throw new IOException("Icon pack target or identity contract mismatch");
        String packId = PackFormat.text(root, "packId");
        PackFormat.digest(packId);
        if (!packId.equals(PackFormat.packId(root))) throw new IOException("Pack digest mismatch");
        List<Page> pages = readPages(zip, root.getAsJsonArray("pages"));
        Map<StableKey, Location> entries = readLocations(root.getAsJsonObject("entries"), pages, archiveEntries);
        int failureCount = validateFailures(root.getAsJsonObject("failures"));
        if (root.has("statistics")) validateStatistics(root.getAsJsonObject("statistics"));
        return new IconPack(zip, metadata, packId, pages, entries, failureCount);
    }

    private static int validateArchiveEntries(@NotNull ZipFile zip) throws IOException {
        Set<String> names = new HashSet<>();
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry entry = all.nextElement();
            if (!names.add(entry.getName()) || names.size() > PackFormat.MAX_KEYS + 1
                || (!entry.getName()
                    .equals("manifest.json")
                    && !entry.getName()
                        .matches("pages/[0-9a-f]{64}\\.png")))
                throw new IOException("Duplicate or unsupported ZIP entry");
        }
        return names.size();
    }

    private static @NotNull List<Page> readPages(@NotNull ZipFile zip, @NotNull JsonArray records) throws IOException {
        List<Page> pages = new ArrayList<>();
        String previous = "";
        for (JsonElement value : records) {
            JsonObject page = value.getAsJsonObject();
            String digest = PackFormat.text(page, "digest");
            PackFormat.digest(digest);
            String entry = PackFormat.text(page, "path");
            int width = PackFormat.integer(page.get("width"));
            int height = PackFormat.integer(page.get("height"));
            if (digest.compareTo(previous) <= 0 || !entry.equals("pages/" + digest + ".png")
                || width < PackFormat.ICON_SIZE
                || height < PackFormat.ICON_SIZE
                || width > PackFormat.MAX_PAGE_DIMENSION
                || height > PackFormat.MAX_PAGE_DIMENSION) throw new IOException("Invalid atlas page");
            previous = digest;
            byte[] png = bytes(zip, entry, PackFormat.MAX_PAGE);
            if (!digest.equals(PackFormat.hash(png))) throw new IOException("Page digest mismatch");
            checkPng(png, width, height);
            pages.add(new Page(digest, entry, width, height, png.length));
        }
        return pages;
    }

    private static @NotNull Map<StableKey, Location> readLocations(@NotNull JsonObject mapping,
        @NotNull List<Page> pages, int archiveEntries) throws IOException {
        if (mapping.entrySet()
            .size() > PackFormat.MAX_KEYS || pages.size()
                > mapping.entrySet()
                    .size()
            || archiveEntries != pages.size() + 1) throw new IOException("Invalid archive entry count");
        Map<StableKey, Location> entries = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : mapping.entrySet()) {
            StableKey key = StableKey.parse(entry.getKey());
            JsonArray coordinate = entry.getValue()
                .getAsJsonArray();
            if (coordinate.size() != LOCATION_FIELDS) throw new IOException("Invalid icon rectangle");
            int pageIndex = PackFormat.integer(coordinate.get(0));
            int x = PackFormat.integer(coordinate.get(1));
            int y = PackFormat.integer(coordinate.get(2));
            if (pageIndex >= pages.size()) throw new IOException("Unknown page index");
            Page page = pages.get(pageIndex);
            if (x > page.width - PackFormat.ICON_SIZE || y > page.height - PackFormat.ICON_SIZE)
                throw new IOException("Icon outside atlas");
            entries.put(key, new Location(page, x, y));
        }
        return entries;
    }

    private static int validateFailures(@NotNull JsonObject failures) throws IOException {
        int failureCount = PackFormat.integer(failures.get("count"));
        JsonArray records = failures.getAsJsonArray("records");
        if (records.size() > PackFormat.MAX_FAILURE_RECORDS || records.size() > failureCount)
            throw new IOException("Invalid failure records");
        for (JsonElement record : records) {
            PackFormat.text(record.getAsJsonObject(), "context");
            PackFormat.text(record.getAsJsonObject(), "reason");
        }
        return failureCount;
    }

    private static void validateStatistics(@NotNull JsonObject statistics) throws IOException {
        JsonObject counts = statistics.getAsJsonObject("counts");
        JsonArray sources = statistics.getAsJsonArray("sources");
        if (counts.entrySet()
            .size() > PackFormat.MAX_STATISTICS || sources.size() > PackFormat.MAX_STATISTICS)
            throw new IOException("Too many statistics");
        for (Map.Entry<String, JsonElement> entry : counts.entrySet()) {
            if (!entry.getKey()
                .matches("[A-Za-z0-9._-]{1,64}")
                || !entry.getValue()
                    .isJsonPrimitive()
                || !entry.getValue()
                    .getAsJsonPrimitive()
                    .isNumber()
                || !entry.getValue()
                    .toString()
                    .matches("0|[1-9][0-9]*"))
                throw new IOException("Invalid statistic");
            try {
                Long.parseLong(
                    entry.getValue()
                        .toString());
            } catch (NumberFormatException e) {
                throw new IOException("Statistic outside supported range", e);
            }
        }
        for (JsonElement source : sources) if (!source.isJsonPrimitive() || !source.getAsJsonPrimitive()
            .isString()
            || !source.getAsString()
                .matches("[A-Za-z0-9._-]{1,64}"))
            throw new IOException("Invalid source name");
    }

    private static @NotNull Metadata metadata(@NotNull JsonObject root) throws IOException {
        JsonObject environment = root.getAsJsonObject("environment");
        Map<String, String> mods = new TreeMap<>();
        JsonObject modValues = environment.getAsJsonObject("mods");
        for (Map.Entry<String, JsonElement> entry : modValues.entrySet())
            mods.put(entry.getKey(), PackFormat.text(modValues, entry.getKey()));
        List<String> resources = new ArrayList<>();
        for (JsonElement value : environment.getAsJsonArray("resourcePacks")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
                .isString()) throw new IOException("Invalid resource provenance");
            String fingerprint = value.getAsString();
            PackFormat.digest(fingerprint);
            resources.add(fingerprint);
        }
        return new Metadata(
            PackFormat.text(root, "minecraftVersion"),
            PackFormat.text(root, "loader"),
            PackFormat.text(root, "identityContract"),
            PackFormat.text(root, "basePolicy"),
            PackFormat.text(root, "generatorVersion"),
            PackFormat.text(root, "generatedAt"),
            mods,
            resources);
    }

    private static byte @NotNull [] bytes(@NotNull ZipFile zip, @NotNull String name, int maximum) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() < 0 || entry.getSize() > maximum)
            throw new IOException("Missing or oversized pack entry");
        if (name.endsWith(".png") && entry.getMethod() != ZipEntry.STORED)
            throw new IOException("Atlas PNG entries must be ZIP STORED");
        byte[] bytes;
        try (InputStream stream = zip.getInputStream(entry)) {
            bytes = PackFormat.read(stream, maximum);
        }
        CRC32 crc = new CRC32();
        crc.update(bytes);
        if (bytes.length != entry.getSize() || crc.getValue() != entry.getCrc())
            throw new IOException("ZIP size or CRC mismatch");
        return bytes;
    }

    private static void checkPng(byte @NotNull [] png, int width, int height) throws IOException {
        if (png.length < PNG_HEADER_LENGTH) throw new IOException("Truncated PNG");
        ByteBuffer header = ByteBuffer.wrap(png);
        if (header.getLong() != PNG_SIGNATURE || header.getInt() != PNG_IHDR_DATA_LENGTH
            || header.getInt() != PNG_IHDR_TYPE
            || header.getInt() != width
            || header.getInt() != height) throw new IOException("PNG header mismatch");
        CRC32 crc = new CRC32();
        crc.update(png, PNG_IHDR_TYPE_OFFSET, PNG_IHDR_CRC_INPUT_LENGTH);
        if ((int) crc.getValue() != ByteBuffer.wrap(png, PNG_IHDR_CRC_OFFSET, Integer.BYTES)
            .getInt()) throw new IOException("PNG header CRC mismatch");
    }

    public @NotNull Metadata metadata() {
        return metadata;
    }

    public @NotNull String packId() {
        return packId;
    }

    public @NotNull List<Page> pages() {
        return pages;
    }

    public @Nullable Location find(@NotNull StableKey key) {
        return entries.get(key);
    }

    public int failureCount() {
        return failures;
    }

    public synchronized @NotNull InputStream openPage(@NotNull String digest) throws IOException {
        if (closing) throw new IOException("Icon pack is closed");
        for (Page page : pages) if (page.digest.equals(digest)) {
            InputStream input = zip.getInputStream(zip.getEntry(page.path));
            readers++;
            return new FilterInputStream(input) {

                private boolean released;

                @Override
                public void close() throws IOException {
                    synchronized (IconPack.this) {
                        if (released) return;
                        released = true;
                        try {
                            super.close();
                        } finally {
                            readers--;
                            if (closing && readers == 0) zip.close();
                        }
                    }
                }
            };
        }
        throw new FileNotFoundException("Unknown atlas page");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closing) return;
        closing = true;
        if (readers == 0) zip.close();
    }

    /** Native platform compatibility requirements, independent of a generated pack's provenance. */
    public static final class Target {

        public final @NotNull String minecraftVersion, loader, identityContract, basePolicy;

        public Target(@NotNull String minecraftVersion, @NotNull String loader, @NotNull String identityContract,
            @NotNull String basePolicy) {
            this.minecraftVersion = minecraftVersion;
            this.loader = loader;
            this.identityContract = identityContract;
            this.basePolicy = basePolicy;
        }
    }

    public static final class Metadata {

        public final @NotNull String minecraftVersion, loader, identityContract, basePolicy, generatorVersion,
            generatedAt;
        public final @NotNull Map<String, String> mods;
        public final @NotNull List<String> resourcePacks;

        public Metadata(@NotNull String minecraftVersion, @NotNull String loader, @NotNull String identityContract,
            @NotNull String basePolicy, @NotNull String generatorVersion, @NotNull String generatedAt,
            @NotNull Map<String, String> mods, @NotNull List<String> resourcePacks) {
            this.minecraftVersion = minecraftVersion;
            this.loader = loader;
            this.identityContract = identityContract;
            this.basePolicy = basePolicy;
            this.generatorVersion = generatorVersion;
            this.generatedAt = generatedAt;
            this.mods = Collections.unmodifiableMap(new TreeMap<>(mods));
            this.resourcePacks = Collections.unmodifiableList(new ArrayList<>(resourcePacks));
        }
    }

    public static final class Page {

        public final @NotNull String digest, path;
        public final int width, height;
        public final transient long bytes;

        Page(@NotNull String digest, @NotNull String path, int width, int height, long bytes) {
            this.digest = digest;
            this.path = path;
            this.width = width;
            this.height = height;
            this.bytes = bytes;
        }
    }

    public static final class Location {

        public final @NotNull Page page;
        public final int x, y;

        Location(@NotNull Page page, int x, int y) {
            this.page = page;
            this.x = x;
            this.y = y;
        }
    }
}
