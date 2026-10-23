package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.OpenGlHelper;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** Saves only touched sprite mip rectangles. Every write is restored before the client can yield. */
final class AtlasFrameScope implements AutoCloseable {

    static final long MAX_SCRATCH_BYTES = 16L * 1024 * 1024;
    private final RenderState state;
    private final List<Rectangle> rectangles = new ArrayList<>();
    private final List<NativeIconAnimation.NativeSprite> sprites;
    private final int texture;
    private int framebuffer;
    private int unpackBuffer;
    private boolean snapshot;
    private boolean modified;

    AtlasFrameScope(int texture, @NotNull List<NativeIconAnimation.NativeSprite> sprites, @NotNull Scratch scratch,
        @NotNull RenderState state) {
        this.state = state;
        this.texture = texture;
        this.sprites = sprites;
        try {
            long totalBytes = 0;
            for (NativeIconAnimation.NativeSprite timeline : sprites) totalBytes += timeline.scratchBytes();
            if (totalBytes > MAX_SCRATCH_BYTES)
                throw new IllegalArgumentException("Sprite mip scratch exceeds animation limit");
            scratch.reserve((int) totalBytes);
            state.snapshot();
            snapshot = true;
            if (PixelReadback.supportsPackBinding()) {
                unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            }
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            pixelStore();
            framebuffer = OpenGlHelper.func_153165_e();
            OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, framebuffer);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            long bytes = 0;
            for (int index = 0; index < sprites.size(); index++) {
                NativeIconAnimation.NativeSprite timeline = sprites.get(index);
                int[][] data = timeline.pixels(0);
                for (int level = 0; level < data.length; level++) {
                    int width = timeline.width(level);
                    int height = timeline.height(level);
                    if (width == 0 || height == 0 || data[level] == null) continue;
                    bytes += (long) width * height * Integer.BYTES;
                    if (bytes > MAX_SCRATCH_BYTES)
                        throw new IllegalArgumentException("Sprite mip scratch exceeds animation limit");
                    Rectangle rectangle = new Rectangle(
                        index,
                        level,
                        (timeline.sprite.getOriginX() >> level) - (timeline.gutter >> level),
                        (timeline.sprite.getOriginY() >> level) - (timeline.gutter >> level),
                        width,
                        height,
                        scratch);
                    OpenGlHelper.func_153188_a(
                        OpenGlHelper.field_153198_e,
                        GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D,
                        texture,
                        level);
                    if (OpenGlHelper.func_153167_i(OpenGlHelper.field_153198_e) != OpenGlHelper.field_153202_i)
                        throw new IllegalStateException("Cannot read sprite atlas mip framebuffer");
                    GL11.glReadPixels(
                        rectangle.x,
                        rectangle.y,
                        width,
                        height,
                        GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE,
                        rectangle.saved);
                    checkError();
                    rectangles.add(rectangle);
                }
            }
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) {
                    if (!(failure instanceof VirtualMachineError) && !(failure instanceof ThreadDeath)
                        && (cleanup instanceof VirtualMachineError || cleanup instanceof ThreadDeath)) {
                        cleanup.addSuppressed(failure);
                        throw cleanup;
                    }
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    void select(int tick) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        modified = true;
        for (Rectangle rectangle : rectangles) {
            rectangle.upload.clear();
            sprites.get(rectangle.sprite)
                .write(tick, rectangle.level, rectangle.upload);
            rectangle.upload.flip();
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D,
                rectangle.level,
                rectangle.x,
                rectangle.y,
                rectangle.width,
                rectangle.height,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                rectangle.upload);
            checkError();
        }
    }

    private static void pixelStore() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_FALSE);
    }

    private static void checkError() {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("Sprite atlas transfer failed: " + error);
    }

    @Override
    public void close() {
        try {
            if (modified) {
                RuntimeException priorError = null;
                try {
                    PixelReadback.discardRenderingErrors();
                } catch (RuntimeException failure) {
                    priorError = failure;
                }
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                if (PixelReadback.supportsPackBinding()) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                pixelStore();
                for (Rectangle rectangle : rectangles) {
                    rectangle.saved.rewind();
                    GL11.glTexSubImage2D(
                        GL11.GL_TEXTURE_2D,
                        rectangle.level,
                        rectangle.x,
                        rectangle.y,
                        rectangle.width,
                        rectangle.height,
                        GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE,
                        rectangle.saved);
                }
                try {
                    checkError();
                } catch (RuntimeException failure) {
                    if (priorError != null && priorError != failure) failure.addSuppressed(priorError);
                    throw failure;
                }
                if (priorError != null) throw priorError;
            }
        } finally {
            try {
                if (framebuffer != 0) OpenGlHelper.func_153174_h(framebuffer);
            } finally {
                if (snapshot) {
                    try {
                        state.close();
                    } finally {
                        if (PixelReadback.supportsPackBinding())
                            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
                        snapshot = false;
                    }
                }
            }
        }
    }

    /** One renderer reuses bounded direct storage; queued cursors own none. */
    static final class Scratch {

        private ByteBuffer bytes = BufferUtils.createByteBuffer(0);

        void reserve(int size) {
            if (bytes.capacity() < size * 2) bytes = BufferUtils.createByteBuffer(size * 2);
            bytes.clear();
        }

        ByteBuffer slice(int size) {
            int position = bytes.position();
            bytes.limit(position + size);
            ByteBuffer result = bytes.slice()
                .order(ByteOrder.nativeOrder());
            bytes.limit(bytes.capacity());
            bytes.position(position + size);
            return result;
        }
    }

    private static final class Rectangle {

        final int sprite, level, x, y, width, height;
        final ByteBuffer saved;
        final IntBuffer upload;

        Rectangle(int sprite, int level, int x, int y, int width, int height, Scratch scratch) {
            this.sprite = sprite;
            this.level = level;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            saved = scratch.slice(width * height * Integer.BYTES);
            upload = scratch.slice(width * height * Integer.BYTES)
                .asIntBuffer();
        }
    }
}
