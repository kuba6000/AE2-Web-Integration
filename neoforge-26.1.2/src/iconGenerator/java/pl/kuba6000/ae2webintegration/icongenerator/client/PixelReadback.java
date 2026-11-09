package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.CaptureKind;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Bounded native GPU readback pages; only detached ARGB pixels leave the render thread. */
final class PixelReadback implements AutoCloseable {

    private static final int PAGE_COUNT = 3;
    private static final int ICONS_PER_PAGE = 16;
    private static final int SIZE = ModernIconRenderer.ICON_SIZE;
    private static final int ICON_BYTES = SIZE * SIZE * Integer.BYTES;
    private final boolean deferred;
    private final List<Page> owned = new ArrayList<>(PAGE_COUNT);
    private final Deque<Page> free = new ArrayDeque<>(PAGE_COUNT);
    private final Deque<Page> pending = new ArrayDeque<>(PAGE_COUNT);
    private @Nullable Page current;
    private long readbackNanos, readbackCount, retrievalNanos, retrievalCount, conversionNanos, conversionCount;

    PixelReadback(boolean deferred) {
        this.deferred = deferred;
    }

    void submit(GpuTexture texture, StableKey key, List<Capture> captures, CaptureKind kind, long timing) {
        if (owned.isEmpty()) {
            for (int index = 0; index < (deferred ? PAGE_COUNT : 1); index++) {
                Page page = new Page(
                    RenderSystem.getDevice()
                        .createBuffer(
                            () -> "ae2webicons readback",
                            GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                            (long) (deferred ? ICONS_PER_PAGE : 1) * ICON_BYTES));
                owned.add(page);
                free.addLast(page);
            }
        }
        if (current == null) {
            if (free.isEmpty()) {
                Page page = pending.removeFirst();
                retrieve(page, captures);
                free.addLast(page);
            }
            current = free.removeFirst();
        }
        Page page = current;
        long started = System.nanoTime();
        try {
            RenderSystem.getDevice()
                .createCommandEncoder()
                .copyTextureToBuffer(texture, page.buffer, (long) page.count * ICON_BYTES, () -> {}, 0);
        } finally {
            readbackNanos += System.nanoTime() - started;
            readbackCount++;
        }
        page.keys[page.count] = key;
        page.kinds[page.count] = kind;
        page.timings[page.count++] = timing;
        if (page.count == (deferred ? ICONS_PER_PAGE : 1)) seal();
        if (!deferred) drain(captures);
    }

    private void seal() {
        Page page = current;
        page.fence = RenderSystem.getDevice()
            .createCommandEncoder()
            .createFence();
        pending.addLast(page);
        current = null;
    }

    void drain(@NotNull List<Capture> captures) {
        if (current != null) seal();
        while (!pending.isEmpty()) {
            Page page = pending.removeFirst();
            retrieve(page, captures);
            free.addLast(page);
        }
    }

    private void retrieve(Page page, List<Capture> captures) {
        long started = System.nanoTime();
        List<Capture> completed = new ArrayList<>(page.count);
        try {
            if (!page.fence.awaitCompletion(Long.MAX_VALUE))
                throw new IllegalStateException("Icon readback fence timed out");
            try (GpuBuffer.MappedView mapped = RenderSystem.getDevice()
                .createCommandEncoder()
                .mapBuffer(page.buffer, true, false)) {
                ByteBuffer bytes = mapped.data();
                for (int index = 0; index < page.count; index++) {
                    completed
                        .add(new Capture(page.keys[index], convert(bytes), page.kinds[index], page.timings[index]));
                }
            }
        } finally {
            page.fence.close();
            page.fence = null;
            retrievalNanos += System.nanoTime() - started;
            retrievalCount++;
        }
        captures.addAll(completed);
        Arrays.fill(page.keys, null);
        Arrays.fill(page.kinds, null);
        page.count = 0;
    }

    @SuppressWarnings("PMD.AvoidMagicNumbers") // Native RGBA bytes become packed Java ARGB.
    private int @NotNull [] convert(@NotNull ByteBuffer bytes) {
        long started = System.nanoTime();
        try {
            int[] pixels = new int[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int red = bytes.get() & 255;
                    int green = bytes.get() & 255;
                    int blue = bytes.get() & 255;
                    int alpha = bytes.get() & 255;
                    // Native GUI blending writes premultiplied RGB to our transparent target; PNG stores straight RGB.
                    if (alpha > 0 && alpha < 255) {
                        red = Math.min(255, (red * 255 + alpha / 2) / alpha);
                        green = Math.min(255, (green * 255 + alpha / 2) / alpha);
                        blue = Math.min(255, (blue * 255 + alpha / 2) / alpha);
                    }
                    pixels[(SIZE - y - 1) * SIZE + x] = alpha == 0 ? 0 : alpha << 24 | red << 16 | green << 8 | blue;
                }
            }
            return pixels;
        } finally {
            conversionNanos += System.nanoTime() - started;
            conversionCount++;
        }
    }

    void statistics(@NotNull Map<String, Long> counts) {
        counts.put("readbackPbo", deferred ? 1L : 0L);
        counts.put("readbackPboPages", deferred ? (long) PAGE_COUNT : 0L);
        counts.put("readbackIconsPerPage", deferred ? (long) ICONS_PER_PAGE : 1L);
        counts.put("renderReadbackNanos", readbackNanos);
        counts.put("renderReadbackCount", readbackCount);
        counts.put("renderRetrievalNanos", retrievalNanos);
        counts.put("renderRetrievalCount", retrievalCount);
        counts.put("renderConversionNanos", conversionNanos);
        counts.put("renderConversionCount", conversionCount);
    }

    @Override
    public void close() {
        Throwable failure = null;
        try {
            if (current != null) seal();
        } catch (RuntimeException | Error problem) {
            failure = problem;
        }
        for (Page page : owned) {
            try (GpuBuffer ignored = page.buffer; GpuFence fence = page.fence) {
                if (fence != null && !fence.awaitCompletion(Long.MAX_VALUE)) {
                    throw new IllegalStateException("Icon readback fence timed out during cleanup");
                }
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (problem instanceof VirtualMachineError || problem instanceof ThreadDeath) {
                    problem.addSuppressed(failure);
                    failure = problem;
                } else failure.addSuppressed(problem);
            }
        }
        owned.clear();
        free.clear();
        pending.clear();
        current = null;
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }

    private static final class Page {

        final GpuBuffer buffer;
        final StableKey[] keys = new StableKey[ICONS_PER_PAGE];
        final CaptureKind[] kinds = new CaptureKind[ICONS_PER_PAGE];
        final long[] timings = new long[ICONS_PER_PAGE];
        @Nullable
        GpuFence fence;
        int count;

        Page(GpuBuffer buffer) {
            this.buffer = buffer;
        }
    }
}
