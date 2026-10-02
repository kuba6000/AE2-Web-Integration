package pl.kuba6000.ae2webintegration.core.icons;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.IconPack.Metadata;
import pl.kuba6000.ae2webintegration.core.icons.IconPack.Page;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Single-worker export writer. add consumes pixels synchronously; close removes unfinished scratch files. */
public final class IconPackWriter implements Closeable {

    private final @NotNull Path outputDirectory;
    private final @NotNull Path scratch;
    private final @NotNull Metadata metadata;
    private final int pageSize;
    private final int columns;
    private final @NotNull Map<String, int[]> entries = new TreeMap<>();
    private final @NotNull List<Page> pages = new ArrayList<>();
    private final @NotNull List<Map<String, String>> failures = new ArrayList<>();
    private final @NotNull Map<String, List<PixelRecord>> pixelIndex = new HashMap<>();
    private final @NotNull Map<String, PixelRecord> keyPixels = new HashMap<>();
    private final @NotNull RandomAccessFile pixelSpool;
    private final @NotNull Map<String, Object> statistics = new TreeMap<>();
    private @Nullable BufferedImage image;
    private int occupied;
    private int failureCount;
    private boolean closed;

    public IconPackWriter(@NotNull Path outputDirectory, @NotNull Metadata metadata, int pageSize) throws IOException {
        if (pageSize != PackFormat.ICON_SIZE && pageSize != PackFormat.SMALL_PAGE_DIMENSION
            && pageSize != PackFormat.DEFAULT_PAGE_DIMENSION
            && pageSize != PackFormat.MAX_PAGE_DIMENSION) throw new IllegalArgumentException("Unsupported page size");
        if (!metadata.minecraftVersion.matches("[A-Za-z0-9._-]+"))
            throw new IllegalArgumentException("Unsafe target filename");
        this.outputDirectory = outputDirectory;
        this.metadata = metadata;
        this.pageSize = pageSize;
        columns = pageSize == PackFormat.ICON_SIZE ? 1 : pageSize / PackFormat.CELL_PITCH;
        Files.createDirectories(outputDirectory);
        scratch = Files.createTempDirectory(outputDirectory, ".ae2wi-export-");
        try {
            pixelSpool = new RandomAccessFile(
                scratch.resolve("pixels.bin")
                    .toFile(),
                "rw");
        } catch (IOException failure) {
            try {
                deleteScratch();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public void add(@NotNull StableKey key, int @NotNull [] pixels) throws IOException {
        checkOpen();
        if (pixels.length != PackFormat.ICON_PIXELS) throw new IllegalArgumentException("Expected 64x64 ARGB pixels");
        byte[] rgba = new byte[PackFormat.ICON_BYTES];
        ByteBuffer.wrap(rgba)
            .asIntBuffer()
            .put(pixels);
        PixelRecord existingKey = keyPixels.get(key.toString());
        if (existingKey != null) {
            if (!samePixels(existingKey, rgba)) throw new IOException("Conflicting icon key");
            return;
        }
        if (entries.size() >= PackFormat.MAX_KEYS) throw new IOException("Too many icon mappings");
        String pixelDigest = PackFormat.hash(rgba);
        List<PixelRecord> matches = pixelIndex.computeIfAbsent(pixelDigest, ignored -> new ArrayList<>());
        for (PixelRecord match : matches) if (samePixels(match, rgba)) {
            entries.put(key.toString(), match.position);
            keyPixels.put(key.toString(), match);
            return;
        }
        int[] position = placePixels(pixels);
        entries.put(key.toString(), position);
        long offset = pixelSpool.length();
        pixelSpool.seek(offset);
        pixelSpool.write(rgba);
        PixelRecord record = new PixelRecord(offset, position);
        keyPixels.put(key.toString(), record);
        matches.add(record);
        occupied++;
        if (occupied == columns * columns) flushPage();
    }

    private int @NotNull [] placePixels(int @NotNull [] pixels) {
        if (image == null) image = new BufferedImage(pageSize, pageSize, BufferedImage.TYPE_INT_ARGB);
        int x = pageSize == PackFormat.ICON_SIZE ? 0 : (occupied % columns) * PackFormat.CELL_PITCH + PackFormat.GUTTER;
        int y = pageSize == PackFormat.ICON_SIZE ? 0 : (occupied / columns) * PackFormat.CELL_PITCH + PackFormat.GUTTER;
        image.setRGB(x, y, PackFormat.ICON_SIZE, PackFormat.ICON_SIZE, pixels, 0, PackFormat.ICON_SIZE);
        if (pageSize != PackFormat.ICON_SIZE) extrudeGutter(image, pixels, x, y);
        return new int[] { pages.size(), x, y };
    }

    private static void extrudeGutter(@NotNull BufferedImage image, int @NotNull [] pixels, int x, int y) {
        int last = PackFormat.ICON_SIZE - 1;
        for (int dx = -PackFormat.GUTTER; dx <= PackFormat.ICON_SIZE; dx++) {
            int sourceX = Math.max(0, Math.min(last, dx));
            image.setRGB(x + dx, y - PackFormat.GUTTER, pixels[sourceX]);
            image.setRGB(x + dx, y + PackFormat.ICON_SIZE, pixels[last * PackFormat.ICON_SIZE + sourceX]);
        }
        for (int dy = 0; dy < PackFormat.ICON_SIZE; dy++) {
            image.setRGB(x - PackFormat.GUTTER, y + dy, pixels[dy * PackFormat.ICON_SIZE]);
            image.setRGB(x + PackFormat.ICON_SIZE, y + dy, pixels[dy * PackFormat.ICON_SIZE + last]);
        }
    }

    private boolean samePixels(@NotNull PixelRecord record, byte @NotNull [] pixels) throws IOException {
        byte[] existing = new byte[pixels.length];
        pixelSpool.seek(record.offset);
        pixelSpool.readFully(existing);
        return Arrays.equals(existing, pixels);
    }

    private static final class PixelRecord {

        final long offset;
        final int @NotNull [] position;

        PixelRecord(long offset, int @NotNull [] position) {
            this.offset = offset;
            this.position = position;
        }
    }

    public void failure(@NotNull String context, @NotNull String reason) throws IOException {
        checkOpen();
        if (failureCount == Integer.MAX_VALUE) throw new IOException("Too many failures");
        failureCount++;
        if (failures.size() < PackFormat.MAX_FAILURE_RECORDS) {
            Map<String, String> record = new TreeMap<>();
            record.put("context", bounded(context));
            record.put("reason", bounded(reason));
            failures.add(record);
        }
    }

    private static @NotNull String bounded(@NotNull String value) {
        return value.substring(0, Math.min(PackFormat.MAX_FAILURE_TEXT, value.length()));
    }

    public void statistics(@NotNull Map<String, Long> counts, @NotNull List<String> sources) throws IOException {
        checkOpen();
        if (counts.size() > PackFormat.MAX_STATISTICS || sources.size() > PackFormat.MAX_STATISTICS)
            throw new IllegalArgumentException("Too many statistics");
        for (Map.Entry<String, Long> entry : counts.entrySet()) if (!entry.getKey()
            .matches("[A-Za-z0-9._-]{1,64}") || entry.getValue() < 0)
            throw new IllegalArgumentException("Invalid statistic");
        for (String source : sources)
            if (!source.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid source name");
        statistics.put("counts", new TreeMap<>(counts));
        statistics.put("sources", new ArrayList<>(sources));
    }

    private void flushPage() throws IOException {
        if (image == null) return;
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", encoded)) throw new IOException("PNG encoder unavailable");
        byte[] bytes = encoded.toByteArray();
        if (bytes.length > PackFormat.MAX_PAGE) throw new IOException("Encoded atlas too large");
        String digest = PackFormat.hash(bytes);
        Files.write(scratch.resolve(digest + ".png"), bytes);
        pages.add(new Page(digest, "pages/" + digest + ".png", pageSize, pageSize, bytes.length));
        image = null;
        occupied = 0;
    }

    public @NotNull Path finish() throws IOException {
        return finish(() -> false);
    }

    public @NotNull Path finish(@NotNull BooleanSupplier canceled) throws IOException {
        return finish(canceled, () -> true);
    }

    /** beginCommit atomically claims publication against cancellation and is called once, after validation. */
    public @NotNull Path finish(@NotNull BooleanSupplier canceled, @NotNull BooleanSupplier beginCommit)
        throws IOException {
        checkOpen();
        checkCanceled(canceled);
        flushPage();
        List<Page> sorted = new ArrayList<>(pages);
        sorted.sort(Comparator.comparing(page -> page.digest));
        Map<String, Object> root = manifest(sorted);
        String packId = PackFormat.packId(
            PackFormat.GSON.toJsonTree(root)
                .getAsJsonObject());
        root.put("packId", packId);
        byte[] manifest = PackFormat.json(root);
        if (manifest.length > PackFormat.MAX_MANIFEST) throw new IOException("Manifest too large");
        Path temporary = Files.createTempFile(outputDirectory, ".ae2wi-pack-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                PackFormat.zipEntry(zip, "manifest.json", manifest, false);
                for (Page page : sorted) {
                    checkCanceled(canceled);
                    PackFormat
                        .zipEntry(zip, page.path, Files.readAllBytes(scratch.resolve(page.digest + ".png")), true);
                }
            }
            checkCanceled(canceled);
            try (IconPack verified = IconPack.open(
                temporary,
                metadata.minecraftVersion,
                metadata.loader,
                metadata.identityContract,
                metadata.basePolicy)) {
                if (!packId.equals(verified.packId())) throw new IOException("Finished pack identity mismatch");
            }
            Path output = outputDirectory
                .resolve("AE2WebIntegration-icons-" + metadata.minecraftVersion + "-" + packId + ".ae2wi-icons");
            // Cleanup must succeed before publication: a visible final archive always means success.
            close();
            checkCanceled(canceled);
            if (!beginCommit.getAsBoolean()) throw new CancellationException("Icon export publication canceled");
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return output;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private @NotNull Map<String, Object> manifest(@NotNull List<Page> sorted) {
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) indices.put(sorted.get(i).digest, i);
        Map<String, int[]> mapping = new TreeMap<>();
        for (Map.Entry<String, int[]> entry : entries.entrySet()) {
            int[] position = entry.getValue();
            mapping.put(
                entry.getKey(),
                new int[] { indices.get(pages.get(position[0]).digest), position[1], position[2] });
        }
        Map<String, Object> root = new TreeMap<>();
        root.put("formatVersion", PackFormat.VERSION);
        root.put("minecraftVersion", metadata.minecraftVersion);
        root.put("loader", metadata.loader);
        root.put("identityContract", metadata.identityContract);
        root.put("basePolicy", metadata.basePolicy);
        root.put("generatorVersion", metadata.generatorVersion);
        root.put("generatedAt", metadata.generatedAt);
        root.put("contentWidth", PackFormat.ICON_SIZE);
        root.put("contentHeight", PackFormat.ICON_SIZE);
        Map<String, Object> environment = new TreeMap<>();
        environment.put("mods", metadata.mods);
        environment.put("resourcePacks", metadata.resourcePacks);
        root.put("environment", environment);
        root.put("entries", mapping);
        List<Map<String, Object>> pageRecords = new ArrayList<>();
        for (Page page : sorted) {
            Map<String, Object> record = new TreeMap<>();
            record.put("digest", page.digest);
            record.put("path", page.path);
            record.put("width", page.width);
            record.put("height", page.height);
            pageRecords.add(record);
        }
        root.put("pages", pageRecords);
        Map<String, Object> summary = new TreeMap<>();
        summary.put("count", failureCount);
        summary.put("records", failures);
        root.put("failures", summary);
        if (!statistics.isEmpty()) root.put("statistics", statistics);
        return root;
    }

    private static void checkCanceled(@NotNull BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) throw new CancellationException("Icon export canceled");
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("Icon writer is closed");
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        image = null;
        IOException failure = null;
        try {
            pixelSpool.close();
        } catch (IOException close) {
            failure = close;
        }
        try {
            deleteScratch();
        } catch (IOException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
    }

    private void deleteScratch() throws IOException {
        IOException failure = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(scratch)) {
            for (Path file : files) try {
                Files.delete(file);
            } catch (IOException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        try {
            Files.delete(scratch);
        } catch (IOException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
    }
}
