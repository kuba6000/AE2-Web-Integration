package pl.kuba6000.ae2webintegration.core.icons;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.IconPack.Metadata;
import pl.kuba6000.ae2webintegration.core.icons.IconPack.Page;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.TempDirectories;

/** Single-owner assembly with bounded parallel PNG encoding. add consumes caller pixels synchronously. */
public final class IconPackWriter implements Closeable {

    private static final int PNG_BUFFER_BUDGET = 160 * 1024 * 1024;
    private static final int MAX_PENDING_PAGES = 10;
    private static final int PNG_BUFFERS_PER_PAGE = 4;

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
    private final byte @NotNull [] serializedPixels = new byte[PackFormat.ICON_BYTES];
    private final byte @NotNull [] comparedPixels = new byte[PackFormat.ICON_BYTES];
    private final @NotNull IntBuffer pixelInts = ByteBuffer.wrap(serializedPixels)
        .asIntBuffer();
    private final @NotNull Map<String, Object> statistics = new TreeMap<>();
    private final @NotNull Map<String, Long> statisticCounts = new TreeMap<>();
    private final int pngWorkers = Math.max(
        4,
        Runtime.getRuntime()
            .availableProcessors() / 2);
    private final int pendingPageLimit;
    private final @NotNull ExecutorService pngExecutor;
    private final @NotNull ArrayDeque<Future<EncodedPage>> pendingPages = new ArrayDeque<>();
    private long pngWorkNanosSum;
    private long pngQueueWaitNanosSum;
    private long pngBackpressureNanos;
    private int peakPendingPages;
    private @Nullable Throwable reportedPngFailure;
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
        // Four raw ARGB buffers per PNG job estimate encoder/output storage; one assembly page is separate.
        pendingPageLimit = Math
            .min(MAX_PENDING_PAGES, PNG_BUFFER_BUDGET / (pageSize * pageSize * Integer.BYTES * PNG_BUFFERS_PER_PAGE));
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
        pngExecutor = Executors.newFixedThreadPool(pngWorkers, task -> new Thread(task, "AE2WI icon PNG"));
    }

    public void add(@NotNull StableKey key, int @NotNull [] pixels) throws IOException {
        checkOpen();
        if (pixels.length != PackFormat.ICON_PIXELS) throw new IllegalArgumentException("Expected 64x64 ARGB pixels");
        pixelInts.clear();
        pixelInts.put(pixels);
        PixelRecord existingKey = keyPixels.get(key.toString());
        if (existingKey != null) {
            if (!samePixels(existingKey)) throw new IOException("Conflicting icon key");
            return;
        }
        if (entries.size() >= PackFormat.MAX_KEYS) throw new IOException("Too many icon mappings");
        String pixelDigest = PackFormat.hash(serializedPixels);
        List<PixelRecord> matches = pixelIndex.computeIfAbsent(pixelDigest, ignored -> new ArrayList<>());
        for (PixelRecord match : matches) if (samePixels(match)) {
            entries.put(key.toString(), match.position);
            keyPixels.put(key.toString(), match);
            return;
        }
        int[] position = placePixels(pixels);
        entries.put(key.toString(), position);
        long offset = pixelSpool.length();
        pixelSpool.seek(offset);
        pixelSpool.write(serializedPixels);
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
        // This writer owns a fresh, contiguous, non-premultiplied ARGB raster.
        int[] atlas = ((DataBufferInt) image.getRaster()
            .getDataBuffer()).getData();
        for (int row = 0; row < PackFormat.ICON_SIZE; row++) {
            System.arraycopy(pixels, row * PackFormat.ICON_SIZE, atlas, (y + row) * pageSize + x, PackFormat.ICON_SIZE);
        }
        if (pageSize != PackFormat.ICON_SIZE) extrudeGutter(atlas, pixels, x, y);
        return new int[] { pages.size() + pendingPages.size(), x, y };
    }

    private void extrudeGutter(int @NotNull [] atlas, int @NotNull [] pixels, int x, int y) {
        int last = PackFormat.ICON_SIZE - 1;
        int top = (y - PackFormat.GUTTER) * pageSize + x;
        int bottom = (y + PackFormat.ICON_SIZE) * pageSize + x;
        for (int dx = -PackFormat.GUTTER; dx <= PackFormat.ICON_SIZE; dx++) {
            int sourceX = Math.max(0, Math.min(last, dx));
            atlas[top + dx] = pixels[sourceX];
            atlas[bottom + dx] = pixels[last * PackFormat.ICON_SIZE + sourceX];
        }
        for (int dy = 0; dy < PackFormat.ICON_SIZE; dy++) {
            int row = (y + dy) * pageSize + x;
            atlas[row - PackFormat.GUTTER] = pixels[dy * PackFormat.ICON_SIZE];
            atlas[row + PackFormat.ICON_SIZE] = pixels[dy * PackFormat.ICON_SIZE + last];
        }
    }

    private boolean samePixels(@NotNull PixelRecord record) throws IOException {
        pixelSpool.seek(record.offset);
        pixelSpool.readFully(comparedPixels);
        return Arrays.equals(comparedPixels, serializedPixels);
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
        statisticCounts.clear();
        statisticCounts.putAll(counts);
        statistics.put("counts", statisticCounts);
        statistics.put("sources", new ArrayList<>(sources));
    }

    private void flushPage() throws IOException {
        if (image == null) return;
        if (pendingPages.size() == pendingPageLimit) {
            long started = System.nanoTime();
            collectPage(() -> false);
            pngBackpressureNanos += System.nanoTime() - started;
        }
        BufferedImage completed = image;
        long submitted = System.nanoTime();
        pendingPages.addLast(pngExecutor.submit(() -> encodePage(completed, submitted)));
        peakPendingPages = Math.max(peakPendingPages, pendingPages.size());
        image = null;
        occupied = 0;
    }

    private @NotNull EncodedPage encodePage(@NotNull BufferedImage completed, long submitted) throws IOException {
        long started = System.nanoTime();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(encoded)) {
            if (!ImageIO.write(completed, "PNG", stream)) throw new IOException("PNG encoder unavailable");
        }
        byte[] bytes = encoded.toByteArray();
        if (bytes.length > PackFormat.MAX_PAGE) throw new IOException("Encoded atlas too large");
        String digest = PackFormat.hash(bytes);
        Files.write(scratch.resolve(digest + ".png"), bytes);
        return new EncodedPage(
            new Page(digest, "pages/" + digest + ".png", pageSize, pageSize, bytes.length),
            System.nanoTime() - started,
            started - submitted);
    }

    private void collectPage(@NotNull BooleanSupplier canceled) throws IOException {
        Future<EncodedPage> pending = pendingPages.getFirst();
        try {
            while (true) {
                checkCanceled(canceled);
                try {
                    EncodedPage result = pending.get(50, TimeUnit.MILLISECONDS);
                    pendingPages.removeFirst();
                    pages.add(result.page);
                    pngWorkNanosSum += result.workNanos;
                    pngQueueWaitNanosSum += result.queueNanos;
                    return;
                } catch (TimeoutException waiting) {
                    // Cancellation is checked while a codec owns the page.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new IOException("Interrupted awaiting PNG page", interrupted);
        } catch (ExecutionException failed) {
            pendingPages.removeFirst();
            reportedPngFailure = failed.getCause();
            throw encodingFailure(failed.getCause());
        }
    }

    private static final class EncodedPage {

        final @NotNull Page page;
        final long workNanos;
        final long queueNanos;

        EncodedPage(@NotNull Page page, long workNanos, long queueNanos) {
            this.page = page;
            this.workNanos = workNanos;
            this.queueNanos = queueNanos;
        }
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
        try {
            return finishPack(canceled, beginCommit);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (IOException | RuntimeException | Error cleanup) {
                if (combineFailures(failure, cleanup) != failure) throw encodingFailure(cleanup);
            }
            throw failure;
        }
    }

    private @NotNull Path finishPack(@NotNull BooleanSupplier canceled, @NotNull BooleanSupplier beginCommit)
        throws IOException {
        checkOpen();
        checkCanceled(canceled);
        flushPage();
        while (!pendingPages.isEmpty()) collectPage(canceled);
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
        recordStatistic("pngWorkers", pngWorkers);
        recordStatistic("pngPeakPendingPages", peakPendingPages);
        recordStatistic("pngWorkNanosSum", pngWorkNanosSum);
        recordStatistic("pngQueueWaitNanosSum", pngQueueWaitNanosSum);
        recordStatistic("pngBackpressureNanos", pngBackpressureNanos);
        statistics.put("counts", statisticCounts);
        statistics.putIfAbsent("sources", new ArrayList<String>());
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

    private void recordStatistic(@NotNull String name, long value) {
        // Preserve the existing 64-count caller allowance; optional diagnostics use available slots.
        if (statisticCounts.size() < PackFormat.MAX_STATISTICS || statisticCounts.containsKey(name))
            statisticCounts.put(name, value);
    }

    private static void checkCanceled(@NotNull BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) throw new CancellationException("Icon export canceled");
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("Icon writer is closed");
        if (reportedPngFailure != null) throw encodingFailure(reportedPngFailure);
        while (!pendingPages.isEmpty() && pendingPages.getFirst()
            .isDone()) collectPage(() -> false);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        image = null;
        Throwable failure = null;
        pngExecutor.shutdown();
        boolean interrupted = false;
        while (!pendingPages.isEmpty()) {
            Future<EncodedPage> pending = pendingPages.removeFirst();
            boolean settled = false;
            while (!settled) try {
                pending.get();
                settled = true;
            } catch (InterruptedException interruption) {
                interrupted = true;
            } catch (ExecutionException encoding) {
                Throwable cause = encoding.getCause();
                if (cause != reportedPngFailure) failure = combineFailures(failure, cause);
                settled = true;
            }
        }
        while (!pngExecutor.isTerminated()) try {
            // The loop condition checks termination; interruptions must not bypass cleanup.
            // noinspection ResultOfMethodCallIgnored
            pngExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interruption) {
            interrupted = true;
        }
        if (interrupted) Thread.currentThread()
            .interrupt();
        try {
            pixelSpool.close();
        } catch (IOException close) {
            failure = combineFailures(failure, close);
        }
        try {
            deleteScratch();
        } catch (IOException cleanup) {
            failure = combineFailures(failure, cleanup);
        }
        if (failure != null) throw encodingFailure(failure);
    }

    private static @NotNull Throwable combineFailures(@Nullable Throwable first, @NotNull Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        boolean firstFatal = first instanceof VirtualMachineError || first instanceof ThreadDeath;
        boolean nextFatal = next instanceof VirtualMachineError || next instanceof ThreadDeath;
        if (nextFatal && !firstFatal || next instanceof Error && !(first instanceof Error)) {
            next.addSuppressed(first);
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static @NotNull IOException encodingFailure(@NotNull Throwable cause) {
        if (cause instanceof Error) throw (Error) cause;
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        return new IOException("Icon pack writing or cleanup failed", cause);
    }

    private void deleteScratch() throws IOException {
        TempDirectories.deleteRecursively(scratch);
    }
}
