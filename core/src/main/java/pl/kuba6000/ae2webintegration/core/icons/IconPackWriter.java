package pl.kuba6000.ae2webintegration.core.icons;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.ANIMATED_VERSION;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.CELL_PITCH;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.DEFAULT_PAGE_DIMENSION;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.GUTTER;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.ICON_BYTES;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.ICON_PIXELS;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.ICON_SIZE;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_ANIMATION_FRAMES;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_ANIMATION_MILLIS;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_ARCHIVE;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_FAILURE_RECORDS;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_FAILURE_TEXT;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_IMAGES;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_KEYS;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_MANIFEST;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_PAGE;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_STATISTICS;
import static pl.kuba6000.ae2webintegration.core.icons.PackFormat.MAX_TOTAL_ANIMATION_FRAMES;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.TempDirectories;

/** Single-owner assembly with bounded parallel PNG encoding. add consumes caller pixels synchronously. */
public final class IconPackWriter implements Closeable {

    private static final Logger LOG = LogManager.getLogger(IconPackWriter.class);
    private static final int PNG_BUFFER_BUDGET = 160 * 1024 * 1024;
    private static final int MAX_PENDING_PAGES = 10;
    private static final int PNG_BUFFERS_PER_PAGE = 4;

    private final @NotNull Path outputDirectory;
    private final @NotNull Path scratch;
    private final @NotNull IconPack.Metadata metadata;
    private final @NotNull Map<String, String> exportMods = new TreeMap<>();
    private static final int PAGE_SIZE = DEFAULT_PAGE_DIMENSION;
    private static final int COLUMNS = PAGE_SIZE / CELL_PITCH;
    /** Maximum distinct images in one exported animation; every clip belongs to one atlas. */
    public static final int MAX_ANIMATION_IMAGES = COLUMNS * COLUMNS;
    private final @NotNull Map<String, int[]> entries = new TreeMap<>();
    private final @NotNull List<IconPack.Page> pages = new ArrayList<>();
    private final @NotNull List<Map<String, String>> failures = new ArrayList<>();
    private final @NotNull Map<String, List<PixelRecord>> pixelIndex = new HashMap<>();
    private final @NotNull Map<String, PixelRecord> keyPixels = new HashMap<>();
    private final @NotNull Map<String, List<AnimationFrame>> animations = new TreeMap<>();
    private final @NotNull Map<String, TimedSample> samples = new HashMap<>();
    private int animationFrames;
    private final @NotNull RandomAccessFile pixelSpool;
    private final byte @NotNull [] serializedPixels = new byte[ICON_BYTES];
    private final byte @NotNull [] comparedPixels = new byte[ICON_BYTES];
    private final @NotNull IntBuffer pixelInts = ByteBuffer.wrap(serializedPixels)
        .asIntBuffer();
    private @NotNull List<String> statisticSources = new ArrayList<>();
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
    private boolean staticSealed;

    public IconPackWriter(@NotNull Path outputDirectory, @NotNull IconPack.Metadata metadata) throws IOException {
        if (!metadata.minecraftVersion()
            .matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Unsafe target filename");
        this.outputDirectory = outputDirectory;
        this.metadata = new IconPack.Metadata(
            metadata.minecraftVersion(),
            metadata.loader(),
            metadata.compatibilityVersion(),
            boundedMetadata(metadata.generatorVersion()),
            boundedMetadata(metadata.generatedAt()),
            Collections.emptyMap());
        long metadataBytes = 0;
        for (Map.Entry<String, String> mod : metadata.mods()
            .entrySet()) {
            if (mod.getKey()
                .length() > 4096
                || mod.getValue()
                    .length() > 4096) {
                failure("metadata", "Oversized optional mod metadata omitted");
                continue;
            }
            int bytes = PackFormat.json(Collections.singletonMap(mod.getKey(), mod.getValue())).length;
            // Reserve manifest space for actual icons instead of retaining unbounded optional provenance.
            if (bytes > MAX_MANIFEST / 4 - metadataBytes) {
                failure("metadata", "Optional mod metadata capacity reached");
                continue;
            }
            metadataBytes += bytes;
            exportMods.put(mod.getKey(), mod.getValue());
        }
        // Four raw ARGB buffers per PNG job estimate encoder/output storage; one assembly page is separate.
        pendingPageLimit = Math
            .min(MAX_PENDING_PAGES, PNG_BUFFER_BUDGET / (PAGE_SIZE * PAGE_SIZE * Integer.BYTES * PNG_BUFFERS_PER_PAGE));
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
        if (staticSealed) throw new IllegalStateException("Static icon phase is sealed");
        serializePixels(pixels);
        PixelRecord existingKey = keyPixels.get(key.toString());
        if (existingKey != null) {
            if (!samePixels(existingKey)) throw new IOException("Conflicting icon key");
            return;
        }
        if (entries.size() >= MAX_KEYS)
            throw new CapacityException(CapacityException.Scope.EXPORT, "Too many icon mappings");
        PixelRecord record = internPixels();
        if (record.staticPosition == null) {
            record.staticPosition = placePixels(pixels);
            occupied++;
            if (occupied == COLUMNS * COLUMNS) flushPage();
        }
        entries.put(key.toString(), record.staticPosition);
        keyPixels.put(key.toString(), record);
    }

    /** Whether a static fallback was admitted; optional captures must have one. */
    public boolean hasStaticIcon(@NotNull StableKey key) {
        return entries.containsKey(key.toString());
    }

    /** Ends static admission and separates optional pages, allowing final capacity fallback without repacking. */
    public void sealStatic() throws IOException {
        checkOpen();
        if (staticSealed) return;
        if (!animations.isEmpty() || !samples.isEmpty())
            throw new IllegalStateException("Static phase must be sealed before animation capture");
        flushPage();
        staticSealed = true;
    }

    /** Appends a timed sample after add established its static fallback. Caller pixels may be reused immediately. */
    public void addAnimationFrame(@NotNull StableKey key, int @NotNull [] pixels, int durationMillis)
        throws IOException {
        checkOpen();
        String identity = key.toString();
        requireBaseline(identity);
        if (samples.containsKey(identity))
            throw new IllegalStateException("Cannot mix timed and explicit animation frames");
        serializePixels(pixels);
        List<AnimationFrame> frames = animations.get(identity);
        AnimationFrame previous = frames == null ? null : frames.get(frames.size() - 1);
        boolean repeated = previous != null && samePixels(previous.pixels());
        checkFrame(identity, repeated, durationMillis, 0);
        PixelRecord record = repeated ? previous.pixels() : internPixels();
        checkImages(identity, record);
        appendFrame(identity, record, durationMillis);
    }

    /** Returns whether the first two samples match. Uses signed nanoTime; retains no caller arrays. */
    public boolean addAnimationSample(@NotNull StableKey key, int @NotNull [] pixels, long submittedNanos)
        throws IOException {
        checkOpen();
        String identity = key.toString();
        requireBaseline(identity);
        TimedSample previous = samples.get(identity);
        if (previous == null && animations.containsKey(identity))
            throw new IllegalStateException("Cannot mix explicit and timed animation frames");
        serializePixels(pixels);
        int interval = 0;
        if (previous == null) {
            if (animationFrames + samples.size() >= MAX_TOTAL_ANIMATION_FRAMES)
                throw new CapacityException(CapacityException.Scope.EXPORT, "Animation frame limit exceeded");
        } else {
            long elapsed = submittedNanos - previous.submittedNanos();
            if (elapsed < 0) throw new IllegalArgumentException("Animation timestamps must be monotonic");
            if (elapsed > MILLISECONDS.toNanos(MAX_ANIMATION_MILLIS))
                throw new CapacityException(CapacityException.Scope.RESOURCE, "Animation interval limit exceeded");
            interval = (int) Math.max(1, NANOSECONDS.toMillis(elapsed));
            checkFrame(identity, repeats(identity, previous.pixels()), interval, 1);
        }
        PixelRecord record = internPixels();
        checkImages(identity, record);
        if (previous != null) appendFrame(identity, previous.pixels(), interval);
        samples.put(identity, new TimedSample(record, submittedNanos, interval));
        return previous != null && previous.previousIntervalMillis() == 0 && previous.pixels() == record;
    }

    private void checkImages(@NotNull String identity, @NotNull PixelRecord additional) throws CapacityException {
        Set<PixelRecord> unique = new HashSet<>();
        List<AnimationFrame> frames = animations.get(identity);
        if (frames != null) for (AnimationFrame frame : frames) unique.add(frame.pixels());
        TimedSample pending = samples.get(identity);
        if (pending != null) unique.add(pending.pixels());
        unique.add(additional);
        if (unique.size() > MAX_ANIMATION_IMAGES)
            throw new CapacityException(CapacityException.Scope.RESOURCE, "Animation images exceed one atlas page");
    }

    private void requireBaseline(@NotNull String identity) {
        if (!entries.containsKey(identity)) throw new IllegalArgumentException("Animation requires a static icon");
    }

    private boolean repeats(@NotNull String identity, @NotNull PixelRecord pixels) {
        List<AnimationFrame> frames = animations.get(identity);
        return frames != null && frames.get(frames.size() - 1)
            .pixels() == pixels;
    }

    private void checkFrame(@NotNull String identity, boolean repeated, int durationMillis, int pendingReservation)
        throws IOException {
        if (durationMillis <= 0) throw new IllegalArgumentException("Invalid animation duration");
        if (durationMillis > MAX_ANIMATION_MILLIS)
            throw new CapacityException(CapacityException.Scope.RESOURCE, "Animation duration limit exceeded");
        List<AnimationFrame> frames = animations.get(identity);
        int duration = 0;
        if (frames != null) for (AnimationFrame frame : frames) duration += frame.durationMillis();
        if (duration > MAX_ANIMATION_MILLIS - durationMillis * (pendingReservation + 1))
            throw new CapacityException(CapacityException.Scope.RESOURCE, "Animation duration limit exceeded");
        if (!repeated && frames != null && frames.size() + pendingReservation >= MAX_ANIMATION_FRAMES)
            throw new CapacityException(CapacityException.Scope.RESOURCE, "Animation frame limit exceeded");
        if (!repeated && animationFrames + samples.size() >= MAX_TOTAL_ANIMATION_FRAMES)
            throw new CapacityException(CapacityException.Scope.EXPORT, "Animation frame limit exceeded");
    }

    private void appendFrame(@NotNull String identity, @NotNull PixelRecord record, int durationMillis) {
        List<AnimationFrame> frames = animations.get(identity);
        boolean repeated = repeats(identity, record);
        if (frames == null) {
            frames = new ArrayList<>();
            animations.put(identity, frames);
        }
        if (repeated) frames.set(
            frames.size() - 1,
            new AnimationFrame(
                record,
                frames.get(frames.size() - 1)
                    .durationMillis() + durationMillis));
        else {
            frames.add(new AnimationFrame(record, durationMillis));
            animationFrames++;
        }
    }

    private void serializePixels(int @NotNull [] pixels) {
        if (pixels.length != ICON_PIXELS) throw new IllegalArgumentException("Expected 64x64 ARGB pixels");
        pixelInts.clear();
        pixelInts.put(pixels);
    }

    /** Keeps the static fallback; discarded pixels remain interned in the bounded spool until close. */
    public void discardAnimation(@NotNull StableKey key) throws IOException {
        checkOpen();
        samples.remove(key.toString());
        List<AnimationFrame> removed = animations.remove(key.toString());
        if (removed != null) animationFrames -= removed.size();
    }

    private @NotNull PixelRecord internPixels() throws IOException {
        String pixelDigest = PackFormat.hash(serializedPixels);
        List<PixelRecord> matches = pixelIndex.get(pixelDigest);
        if (matches != null) for (PixelRecord match : matches) if (samePixels(match)) {
            return match;
        }
        if (pixelSpool.length() / ICON_BYTES >= MAX_IMAGES)
            throw new CapacityException(CapacityException.Scope.EXPORT, "Too many distinct icon images");
        long offset = pixelSpool.length();
        pixelSpool.seek(offset);
        pixelSpool.write(serializedPixels);
        PixelRecord record = new PixelRecord(offset);
        pixelIndex.computeIfAbsent(pixelDigest, ignored -> new ArrayList<>())
            .add(record);
        return record;
    }

    private int @NotNull [] placePixels(int @NotNull [] pixels) {
        if (image == null) image = new BufferedImage(PAGE_SIZE, PAGE_SIZE, TYPE_INT_ARGB);
        int x = (occupied % COLUMNS) * CELL_PITCH + GUTTER;
        int y = (occupied / COLUMNS) * CELL_PITCH + GUTTER;
        // This writer owns a fresh, contiguous, non-premultiplied ARGB raster.
        int[] atlas = ((DataBufferInt) image.getRaster()
            .getDataBuffer()).getData();
        for (int row = 0; row < ICON_SIZE; row++) {
            System.arraycopy(pixels, row * ICON_SIZE, atlas, (y + row) * PAGE_SIZE + x, ICON_SIZE);
        }
        extrudeGutter(atlas, pixels, x, y);
        return new int[] { pages.size() + pendingPages.size(), x, y };
    }

    private void extrudeGutter(int @NotNull [] atlas, int @NotNull [] pixels, int x, int y) {
        int last = ICON_SIZE - 1;
        int top = (y - GUTTER) * PAGE_SIZE + x;
        int bottom = (y + ICON_SIZE) * PAGE_SIZE + x;
        for (int dx = -GUTTER; dx <= ICON_SIZE; dx++) {
            int sourceX = Math.max(0, Math.min(last, dx));
            atlas[top + dx] = pixels[sourceX];
            atlas[bottom + dx] = pixels[last * ICON_SIZE + sourceX];
        }
        for (int dy = 0; dy < ICON_SIZE; dy++) {
            int row = (y + dy) * PAGE_SIZE + x;
            atlas[row - GUTTER] = pixels[dy * ICON_SIZE];
            atlas[row + ICON_SIZE] = pixels[dy * ICON_SIZE + last];
        }
    }

    private boolean samePixels(@NotNull PixelRecord record) throws IOException {
        pixelSpool.seek(record.offset);
        pixelSpool.readFully(comparedPixels);
        return Arrays.equals(comparedPixels, serializedPixels);
    }

    private static final class PixelRecord {

        final long offset;
        int @Nullable [] staticPosition;

        PixelRecord(long offset) {
            this.offset = offset;
        }
    }

    @Desugar
    private record AnimationFrame(@NotNull PixelRecord pixels, int durationMillis, int @Nullable [] position) {

        AnimationFrame(@NotNull PixelRecord pixels, int durationMillis) {
            this(pixels, durationMillis, null);
        }
    }

    @Desugar
    private record TimedSample(@NotNull PixelRecord pixels, long submittedNanos, int previousIntervalMillis) {}

    /** A declared format/export limit, distinct from failures reading, encoding or writing data. */
    public static final class CapacityException extends IOException {

        public enum Scope {
            RESOURCE,
            EXPORT
        }

        private final @NotNull Scope scope;
        private long excessBytes;
        private boolean manifestLimit;

        public CapacityException(@NotNull Scope scope, @NotNull String message) {
            super(message);
            this.scope = scope;
        }

        public @NotNull Scope scope() {
            return scope;
        }
    }

    private void finishSamples() throws IOException {
        Iterator<Map.Entry<String, TimedSample>> iterator = samples.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TimedSample> entry = iterator.next();
            String key = entry.getKey();
            TimedSample sample = entry.getValue();
            iterator.remove();
            if (sample.previousIntervalMillis() == 0) continue;
            try {
                checkFrame(key, repeats(key, sample.pixels()), sample.previousIntervalMillis(), 0);
                appendFrame(key, sample.pixels(), sample.previousIntervalMillis());
            } catch (CapacityException limit) {
                discardAnimation(StableKey.parse(key));
                failure(key + "/animation", limit.getMessage());
            }
        }
    }

    public void failure(@NotNull String context, @NotNull String reason) throws IOException {
        checkOpen();
        if (failureCount < Integer.MAX_VALUE) failureCount++;
        if (failures.size() < MAX_FAILURE_RECORDS) {
            LOG.warn("Icon export omission ({}): {}", bounded(context), bounded(reason));
            Map<String, String> record = new TreeMap<>();
            record.put("context", bounded(context));
            record.put("reason", bounded(reason));
            failures.add(record);
        }
    }

    /** Includes writer-side capacity omissions; remains available after successful publication. */
    public int failureCount() {
        return failureCount;
    }

    private @NotNull String boundedMetadata(@NotNull String value) throws IOException {
        if (value.length() <= 4096) return value;
        failure("metadata", "Oversized optional metadata text shortened");
        return value.substring(0, 4096);
    }

    private static @NotNull String bounded(@NotNull String value) {
        return value.substring(0, Math.min(MAX_FAILURE_TEXT, value.length()));
    }

    public void statistics(@NotNull Map<String, Long> counts, @NotNull List<String> sources) throws IOException {
        checkOpen();
        statisticCounts.clear();
        statisticSources = new ArrayList<>();
        for (Map.Entry<String, Long> entry : new TreeMap<>(counts).entrySet()) {
            if (!entry.getKey()
                .matches("[A-Za-z0-9._-]{1,64}") || entry.getValue() < 0 || statisticCounts.size() >= MAX_STATISTICS) {
                failure("statistics", "Optional statistic omitted: " + entry.getKey());
            } else statisticCounts.put(entry.getKey(), entry.getValue());
        }
        for (String source : sources) {
            if (!source.matches("[A-Za-z0-9._-]{1,64}") || statisticSources.size() >= MAX_STATISTICS) {
                failure("statistics", "Optional source omitted: " + source);
            } else statisticSources.add(source);
        }
    }

    private void flushPage() throws IOException {
        flushPage(() -> false);
    }

    private void flushPage(@NotNull BooleanSupplier canceled) throws IOException {
        if (image == null) return;
        if (pendingPages.size() == pendingPageLimit) {
            long started = System.nanoTime();
            collectPage(canceled);
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
        String digest = PackFormat.hash(bytes);
        // Keep its index for pending references; final admission omits an oversized page and its users.
        if (bytes.length <= MAX_PAGE) Files.write(scratch.resolve(digest + ".png"), bytes);
        return new EncodedPage(
            new IconPack.Page(
                digest,
                "pages/" + digest + ".png",
                completed.getWidth(),
                completed.getHeight(),
                bytes.length),
            System.nanoTime() - started,
            started - submitted);
    }

    private void collectPage(@NotNull BooleanSupplier canceled) throws IOException {
        Future<EncodedPage> pending = pendingPages.getFirst();
        try {
            while (true) {
                checkCanceled(canceled);
                try {
                    EncodedPage result = pending.get(50, MILLISECONDS);
                    pendingPages.removeFirst();
                    pages.add(result.page());
                    pngWorkNanosSum += result.workNanos();
                    pngQueueWaitNanosSum += result.queueNanos();
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

    @Desugar
    private record EncodedPage(@NotNull IconPack.Page page, long workNanos, long queueNanos) {}

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
        finishSamples();
        flushPage(canceled);
        layoutAnimations(canceled);
        flushPage(canceled);
        while (!pendingPages.isEmpty()) collectPage(canceled);
        omitOversizedPages(canceled);
        Path temporary = Files.createTempFile(outputDirectory, ".ae2wi-pack-", ".tmp");
        try {
            String packId;
            while (true) {
                try {
                    packId = writeArchive(temporary, canceled);
                    break;
                } catch (CapacityException limit) {
                    int omittedAnimations = 0;
                    for (List<AnimationFrame> frames : animations.values()) {
                        if (frames.size() > 1) omittedAnimations++;
                    }
                    if (omittedAnimations > 0) {
                        animations.clear();
                        animationFrames = 0;
                        capacityOmissions("animations", omittedAnimations, limit.getMessage());
                    } else if (limit.manifestLimit && !exportMods.isEmpty()) {
                        long remaining = Math.max(1, limit.excessBytes);
                        int omitted = 0;
                        for (String key : new TreeMap<>(exportMods).descendingKeySet()) {
                            checkCanceled(canceled);
                            remaining -= PackFormat.json(Collections.singletonMap(key, exportMods.remove(key))).length
                                - 1;
                            omitted++;
                            if (remaining <= 0) break;
                        }
                        capacityOmissions("metadata", omitted, limit.getMessage());
                    } else if (!entries.isEmpty()) {
                        pruneStatic(limit, canceled);
                    } else throw limit;
                }
            }
            checkCanceled(canceled);
            try (IconPack verified = IconPack
                .open(temporary, metadata.minecraftVersion(), metadata.loader(), metadata.compatibilityVersion())) {
                if (!packId.equals(verified.packId())) throw new IOException("Finished pack identity mismatch");
            }
            Path output = outputDirectory
                .resolve("AE2WebIntegration-icons-" + metadata.minecraftVersion() + "-" + packId + ".ae2wi-icons");
            // Settle workers and handles before publication; leftover scratch files are only a cleanup warning.
            close();
            checkCanceled(canceled);
            if (!beginCommit.getAsBoolean()) throw new CancellationException("Icon export publication canceled");
            Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING);
            if (failureCount != 0)
                LOG.warn("Icon export completed with {} omissions or recoverable failures", failureCount);
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

    private void omitOversizedPages(BooleanSupplier canceled) throws IOException {
        int omitted = 0;
        Iterator<Map.Entry<String, int[]>> statics = entries.entrySet()
            .iterator();
        while (statics.hasNext()) {
            checkCanceled(canceled);
            Map.Entry<String, int[]> entry = statics.next();
            if (pages.get(entry.getValue()[0])
                .bytes() <= MAX_PAGE) continue;
            animations.remove(entry.getKey());
            keyPixels.remove(entry.getKey());
            statics.remove();
            omitted++;
        }
        Iterator<Map.Entry<String, List<AnimationFrame>>> clips = animations.entrySet()
            .iterator();
        while (clips.hasNext()) {
            checkCanceled(canceled);
            Map.Entry<String, List<AnimationFrame>> entry = clips.next();
            boolean oversized = false;
            for (AnimationFrame frame : entry.getValue()) {
                if (frame.position() != null && pages.get(frame.position()[0])
                    .bytes() > MAX_PAGE) {
                    oversized = true;
                    break;
                }
            }
            if (oversized) {
                clips.remove();
                omitted++;
            }
        }
        if (omitted != 0) capacityOmissions("atlas-pages", omitted, "Encoded atlas exceeds page capacity");
    }

    private void capacityOmissions(String context, int omitted, String reason) throws IOException {
        failure(context, "Omitted " + omitted + ": " + reason);
        failureCount = (int) Math.min(Integer.MAX_VALUE, (long) failureCount + omitted - 1);
    }

    private void pruneStatic(CapacityException limit, BooleanSupplier canceled) throws IOException {
        long remaining = Math.max(1, limit.excessBytes);
        int omitted = 0;
        if (limit.manifestLimit) {
            for (String key : new TreeMap<>(entries).descendingKeySet()) {
                checkCanceled(canceled);
                remaining -= PackFormat.json(Collections.singletonMap(key, entries.remove(key))).length - 1;
                keyPixels.remove(key);
                omitted++;
                if (remaining <= 0) break;
            }
        } else {
            Set<String> droppedPages = new HashSet<>();
            List<IconPack.Page> referenced = referencedPages();
            Map<String, Integer> pageOrder = new HashMap<>();
            for (int index = 0; index < pages.size(); index++) pageOrder.put(
                pages.get(index)
                    .digest(),
                index);
            referenced.sort(Comparator.comparingInt(page -> pageOrder.get(page.digest())));
            for (int index = referenced.size() - 1; index >= 0 && remaining > 0; index--) {
                checkCanceled(canceled);
                IconPack.Page page = referenced.get(index);
                droppedPages.add(page.digest());
                remaining -= page.bytes();
            }
            Iterator<Map.Entry<String, int[]>> iterator = entries.entrySet()
                .iterator();
            while (iterator.hasNext()) {
                checkCanceled(canceled);
                Map.Entry<String, int[]> entry = iterator.next();
                if (droppedPages.contains(
                    pages.get(entry.getValue()[0])
                        .digest())) {
                    keyPixels.remove(entry.getKey());
                    iterator.remove();
                    omitted++;
                }
            }
        }
        capacityOmissions("static-icons", omitted, limit.getMessage());
    }

    private @NotNull String writeArchive(@NotNull Path temporary, @NotNull BooleanSupplier canceled)
        throws IOException {
        checkCanceled(canceled);
        List<IconPack.Page> sorted = referencedPages();
        sorted.sort(Comparator.comparing(IconPack.Page::digest));
        Map<String, Object> root = manifest(sorted);
        String packId = PackFormat.packId(
            PackFormat.GSON.toJsonTree(root)
                .getAsJsonObject());
        root.put("packId", packId);
        byte[] manifest = PackFormat.json(root);
        if (manifest.length > MAX_MANIFEST) {
            CapacityException limit = new CapacityException(CapacityException.Scope.EXPORT, "Manifest too large");
            limit.manifestLimit = true;
            limit.excessBytes = manifest.length - MAX_MANIFEST;
            throw limit;
        }
        ArchiveOutput output = new ArchiveOutput(Files.newOutputStream(temporary));
        long projectedBytes;
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            PackFormat.zipEntry(zip, "manifest.json", manifest, false);
            // Actual compressed manifest plus conservative ZIP directory/header overhead.
            projectedBytes = output.written + 128;
            for (IconPack.Page page : sorted) projectedBytes += page.bytes() + page.path()
                .length() * 2L + 128;
            for (IconPack.Page page : sorted) {
                checkCanceled(canceled);
                if (output.exceeded) break;
                PackFormat
                    .zipEntry(zip, page.path(), Files.readAllBytes(scratch.resolve(page.digest() + ".png")), true);
            }
        }
        if (output.exceeded) {
            CapacityException limit = new CapacityException(
                CapacityException.Scope.EXPORT,
                "Icon archive exceeds size limit");
            limit.excessBytes = Math.max(1, projectedBytes - MAX_ARCHIVE);
            throw limit;
        }
        return packId;
    }

    /** Each complete sequence stays within one page; optional pixels have not been PNG-encoded yet. */
    private void layoutAnimations(@NotNull BooleanSupplier canceled) throws IOException {
        Map<PixelRecord, int[]> positions = new HashMap<>();
        int[] pixels = new int[ICON_PIXELS];
        TreeMap<Integer, ArrayDeque<AnimationLayout>> remaining = new TreeMap<>();
        for (List<AnimationFrame> frames : animations.values()) {
            checkCanceled(canceled);
            if (frames.size() < 2) continue;
            LinkedHashSet<PixelRecord> unique = new LinkedHashSet<>();
            for (AnimationFrame frame : frames) unique.add(frame.pixels());
            remaining.computeIfAbsent(unique.size(), ignored -> new ArrayDeque<>())
                .add(new AnimationLayout(frames, unique));
        }
        while (!remaining.isEmpty()) {
            checkCanceled(canceled);
            AnimationLayout selected = null;
            for (ArrayDeque<AnimationLayout> bucket : remaining.descendingMap()
                .values()) {
                AnimationLayout candidate = bucket.getFirst();
                int missing = 0;
                for (PixelRecord record : candidate.unique()) if (!positions.containsKey(record)) missing++;
                if (occupied + missing <= MAX_ANIMATION_IMAGES) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                flushPage(canceled);
                positions.clear();
                continue;
            }
            ArrayDeque<AnimationLayout> bucket = remaining.get(
                selected.unique()
                    .size());
            bucket.removeFirst();
            if (bucket.isEmpty()) remaining.remove(
                selected.unique()
                    .size());
            List<AnimationFrame> frames = selected.frames();
            LinkedHashSet<PixelRecord> unique = selected.unique();
            for (PixelRecord record : unique) {
                checkCanceled(canceled);
                if (positions.containsKey(record)) continue;
                pixelSpool.seek(record.offset);
                pixelSpool.readFully(serializedPixels);
                pixelInts.clear();
                pixelInts.get(pixels);
                positions.put(record, placePixels(pixels));
                occupied++;
            }
            for (int index = 0; index < frames.size(); index++) {
                AnimationFrame frame = frames.get(index);
                frames.set(
                    index,
                    new AnimationFrame(frame.pixels(), frame.durationMillis(), positions.get(frame.pixels())));
            }
        }
    }

    @Desugar
    private record AnimationLayout(@NotNull List<AnimationFrame> frames, @NotNull LinkedHashSet<PixelRecord> unique) {}

    /** Count actual ZIP headers/compression; discard overflow until ZipOutputStream closes cleanly. */
    private static final class ArchiveOutput extends OutputStream {

        private final OutputStream output;
        private long written;
        private boolean exceeded;

        private ArchiveOutput(OutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int value) throws IOException {
            if (exceeded || written == MAX_ARCHIVE) {
                exceeded = true;
                return;
            }
            output.write(value);
            written++;
        }

        @Override
        public void write(byte @NotNull [] bytes, int offset, int length) throws IOException {
            if (exceeded || length > MAX_ARCHIVE - written) {
                exceeded = true;
                return;
            }
            output.write(bytes, offset, length);
            written += length;
        }

        @Override
        public void close() throws IOException {
            output.close();
        }
    }

    private @NotNull List<IconPack.Page> referencedPages() {
        boolean[] referenced = new boolean[pages.size()];
        for (int[] position : entries.values()) referenced[position[0]] = true;
        for (List<AnimationFrame> frames : animations.values()) if (frames.size() > 1) {
            for (AnimationFrame frame : frames) {
                assert frame.position() != null : "Animation layout must finish before publication";
                referenced[frame.position()[0]] = true;
            }
        }
        Map<String, IconPack.Page> result = new HashMap<>();
        for (int index = 0; index < pages.size(); index++) if (referenced[index]) {
            IconPack.Page page = pages.get(index);
            result.put(page.digest(), page);
        }
        return new ArrayList<>(result.values());
    }

    private @NotNull Map<String, Object> manifest(@NotNull List<IconPack.Page> sorted) throws IOException {
        recordStatistic("failures", failureCount);
        recordStatistic("pngWorkers", pngWorkers);
        recordStatistic("pngPeakPendingPages", peakPendingPages);
        recordStatistic("pngWorkNanosSum", pngWorkNanosSum);
        recordStatistic("pngQueueWaitNanosSum", pngQueueWaitNanosSum);
        recordStatistic("pngBackpressureNanos", pngBackpressureNanos);
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) indices.put(
            sorted.get(i)
                .digest(),
            i);
        Map<String, int[]> mapping = new TreeMap<>();
        for (Map.Entry<String, int[]> entry : entries.entrySet()) {
            int[] position = entry.getValue();
            mapping.put(
                entry.getKey(),
                new int[] { indices.get(
                    pages.get(position[0])
                        .digest()),
                    position[1], position[2] });
        }
        Map<String, Object> root = new TreeMap<>();
        Map<String, List<int[]>> sequences = new TreeMap<>();
        for (Map.Entry<String, List<AnimationFrame>> entry : animations.entrySet()) {
            if (entry.getValue()
                .size() < 2) continue;
            List<int[]> sequence = new ArrayList<>();
            for (AnimationFrame frame : entry.getValue()) {
                int[] position = frame.position();
                assert position != null : "Animation layout must finish before publication";
                sequence.add(
                    new int[] { indices.get(
                        pages.get(position[0])
                            .digest()),
                        position[1], position[2], frame.durationMillis() });
            }
            sequences.put(entry.getKey(), sequence);
        }
        root.put("formatVersion", sequences.isEmpty() ? PackFormat.VERSION : ANIMATED_VERSION);
        if (!sequences.isEmpty()) root.put("animations", sequences);
        root.put("minecraftVersion", metadata.minecraftVersion());
        root.put("loader", metadata.loader());
        root.put("compatibilityVersion", metadata.compatibilityVersion());
        root.put("generatorVersion", metadata.generatorVersion());
        root.put("generatedAt", metadata.generatedAt());
        root.put("contentWidth", ICON_SIZE);
        root.put("contentHeight", ICON_SIZE);
        Map<String, Object> environment = new TreeMap<>();
        environment.put("mods", exportMods);
        // Released format-2 readers require this array, even though input fingerprints are no longer collected.
        environment.put("resourcePacks", new String[0]);
        root.put("environment", environment);
        root.put("entries", mapping);
        List<Map<String, Object>> pageRecords = new ArrayList<>();
        for (IconPack.Page page : sorted) {
            Map<String, Object> record = new TreeMap<>();
            record.put("digest", page.digest());
            record.put("path", page.path());
            record.put("width", page.width());
            record.put("height", page.height());
            pageRecords.add(record);
        }
        root.put("pages", pageRecords);
        Map<String, Object> summary = new TreeMap<>();
        summary.put("count", failureCount);
        summary.put("records", failures);
        root.put("failures", summary);
        Map<String, Object> statistics = new TreeMap<>();
        statistics.put("counts", statisticCounts);
        statistics.put("sources", statisticSources);
        root.put("statistics", statistics);
        return root;
    }

    private void recordStatistic(@NotNull String name, long value) {
        // Preserve the existing 64-count caller allowance; optional diagnostics use available slots.
        if (statisticCounts.size() < MAX_STATISTICS || statisticCounts.containsKey(name))
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
            pngExecutor.awaitTermination(1, SECONDS);
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
            LOG.warn("Could not remove icon export scratch directory {}", scratch, cleanup);
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
