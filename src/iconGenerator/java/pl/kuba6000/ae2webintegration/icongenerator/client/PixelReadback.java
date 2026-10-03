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
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Client-owned, bounded readback pages. Only detached pixels leave the GL thread. */
final class PixelReadback implements AutoCloseable {

    private static final int PAGE_COUNT = 3;
    private static final int ICONS_PER_PAGE = 16;
    private static final int SIZE = LegacyIconRenderer.ICON_SIZE;
    private static final int ICON_BYTES = SIZE * SIZE * Integer.BYTES;
    private static final int PAGE_BYTES = ICONS_PER_PAGE * ICON_BYTES;
    private static final int MAX_PRIOR_ERRORS = 16;
    private final boolean requested = requested();
    private final ByteBuffer rgba = BufferUtils.createByteBuffer(ICON_BYTES);
    private final List<Page> owned = new ArrayList<>(PAGE_COUNT);
    private final Deque<Page> free = new ArrayDeque<>(PAGE_COUNT);
    private final Deque<Page> pending = new ArrayDeque<>(PAGE_COUNT);
    private @Nullable Page current;
    private boolean initialized;
    private boolean pbo;
    private long readbackNanos;
    private long readbackCount;
    private long retrievalNanos;
    private long retrievalCount;
    private long conversionNanos;
    private long conversionCount;

    private static boolean requested() {
        String mode = System.getProperty("ae2webicons.readback", "pbo");
        if ("pbo".equals(mode)) return true;
        if ("direct".equals(mode)) return false;
        throw new IllegalArgumentException("ae2webicons.readback must be direct or pbo");
    }

    static boolean supportsPackBinding() {
        return GLContext.getCapabilities().OpenGL21
            || GLContext.getCapabilities().OpenGL15 && GLContext.getCapabilities().GL_ARB_pixel_buffer_object;
    }

    /** Called inside the renderer's per-icon state guard, after successful drawing. */
    void submit(@NotNull StableKey key, @NotNull List<Capture> captures) {
        discardRenderingErrors();
        initialize();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        if (!pbo) {
            if (supportsPackBinding()) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            rgba.clear();
            long started = System.nanoTime();
            try {
                GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
                checkError();
            } finally {
                readbackNanos += System.nanoTime() - started;
                readbackCount++;
            }
            captures.add(new Capture(key, convert(rgba)));
            return;
        }
        if (current == null) {
            if (free.isEmpty()) {
                Page oldest = pending.removeFirst();
                retrieve(oldest, captures);
                free.addLast(oldest);
            }
            current = free.removeFirst();
        }
        Page page = current;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, page.buffer);
        long started = System.nanoTime();
        try {
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (long) page.count * ICON_BYTES);
            checkError();
        } finally {
            readbackNanos += System.nanoTime() - started;
            readbackCount++;
        }
        page.keys[page.count++] = key;
        if (page.count == ICONS_PER_PAGE) {
            pending.addLast(page);
            current = null;
        }
    }

    private void initialize() {
        if (initialized) return;
        pbo = requested && GLContext.getCapabilities().OpenGL21;
        if (pbo) {
            for (int index = 0; index < PAGE_COUNT; index++) {
                Page page = new Page(GL15.glGenBuffers());
                owned.add(page);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, page.buffer);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, PAGE_BYTES, GL15.GL_STREAM_READ);
                checkError();
                free.addLast(page);
            }
        }
        initialized = true;
    }

    /** Drain the partial page too, before transferring a writer batch or publishing the pack. */
    void drain(@NotNull List<Capture> captures) {
        if (current != null) {
            pending.addLast(current);
            current = null;
        }
        if (!pending.isEmpty()) discardRenderingErrors();
        while (!pending.isEmpty()) {
            Page page = pending.removeFirst();
            retrieve(page, captures);
            free.addLast(page);
        }
    }

    private void retrieve(@NotNull Page page, @NotNull List<Capture> captures) {
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, page.buffer);
            List<Capture> completed = new ArrayList<>(page.count);
            ByteBuffer mapped;
            long started = System.nanoTime();
            try {
                mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, PAGE_BYTES, page.mapping);
            } finally {
                retrievalNanos += System.nanoTime() - started;
                retrievalCount++;
            }
            if (mapped == null) {
                checkError();
                throw new IllegalStateException("Could not map icon readback page");
            }
            page.mapping = mapped;
            // Keep this page private until unmap confirms its contents remained valid.
            Throwable conversionFailure = null;
            try {
                checkError();
                mapped.clear();
                for (int index = 0; index < page.count; index++) {
                    completed.add(new Capture(page.keys[index], convert(mapped)));
                }
            } catch (RuntimeException | Error failure) {
                conversionFailure = failure;
                throw failure;
            } finally {
                started = System.nanoTime();
                try {
                    boolean valid = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    checkError();
                    if (!valid) throw new IllegalStateException("Icon readback page contents became invalid");
                } catch (RuntimeException | Error failure) {
                    if (conversionFailure == null) throw failure;
                    conversionFailure.addSuppressed(failure);
                } finally {
                    retrievalNanos += System.nanoTime() - started;
                }
            }
            captures.addAll(completed);
            Arrays.fill(page.keys, null);
            page.count = 0;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
        }
    }

    @SuppressWarnings("PMD.AvoidMagicNumbers") // GL RGBA bytes become packed Java ARGB.
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
                    pixels[(SIZE - y - 1) * SIZE + x] = alpha == 0 ? 0 : alpha << 24 | red << 16 | green << 8 | blue;
                }
            }
            return pixels;
        } finally {
            conversionNanos += System.nanoTime() - started;
            conversionCount++;
        }
    }

    private static void checkError() {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("OpenGL icon readback error: " + error);
    }

    private static void discardRenderingErrors() {
        // Mod renderers can leave sticky flags, including when their draw throws. Attribute only new errors to
        // readback.
        // Never swallow out-of-memory/context-loss errors or loop indefinitely on an unhealthy context.
        for (int count = 0; count < MAX_PRIOR_ERRORS; count++) {
            int error = GL11.glGetError();
            if (error == GL11.GL_NO_ERROR) return;
            if (error != GL11.GL_INVALID_ENUM && error != GL11.GL_INVALID_VALUE
                && error != GL11.GL_INVALID_OPERATION
                && error != GL11.GL_STACK_OVERFLOW
                && error != GL11.GL_STACK_UNDERFLOW
                && error != GL30.GL_INVALID_FRAMEBUFFER_OPERATION) {
                throw new IllegalStateException("Unrecoverable OpenGL error before icon readback: " + error);
            }
        }
        throw new IllegalStateException("OpenGL error state did not clear before icon readback");
    }

    void statistics(@NotNull Map<String, Long> counts) {
        counts.put("readbackPbo", pbo ? 1L : 0L);
        counts.put("readbackPboPages", pbo ? (long) PAGE_COUNT : 0L);
        counts.put("readbackIconsPerPage", pbo ? (long) ICONS_PER_PAGE : 0L);
        counts.put("renderReadbackNanos", readbackNanos);
        counts.put("renderReadbackCount", readbackCount);
        counts.put("renderRetrievalNanos", retrievalNanos);
        counts.put("renderRetrievalCount", retrievalCount);
        counts.put("renderConversionNanos", conversionNanos);
        counts.put("renderConversionCount", conversionCount);
    }

    @Override
    public void close() {
        for (Page page : owned) GL15.glDeleteBuffers(page.buffer);
        owned.clear();
        free.clear();
        pending.clear();
        current = null;
    }

    private static final class Page {

        final int buffer;
        final StableKey[] keys = new StableKey[ICONS_PER_PAGE];
        private @Nullable ByteBuffer mapping;
        private int count;

        Page(int buffer) {
            this.buffer = buffer;
        }
    }
}
