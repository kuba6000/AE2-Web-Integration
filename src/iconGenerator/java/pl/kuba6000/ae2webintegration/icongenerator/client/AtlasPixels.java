package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.systems.RenderSystem;

/** Actual GPU rectangles, scoped to one controlled draw. No live ticker or full atlas is copied. */
final class AtlasPixels implements AutoCloseable {

    private final List<Region> regions = new ArrayList<>();
    private int readFramebuffer;
    private int drawFramebuffer;
    private final boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
    private boolean changed;

    AtlasPixels(@NotNull Minecraft minecraft, @NotNull List<TextureAtlasSprite> sprites) {
        try {
            PixelReadback.discardRenderingErrors();
            readFramebuffer = GL30.glGenFramebuffers();
            drawFramebuffer = GL30.glGenFramebuffers();
            RenderSystem.disableScissor();
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (TextureAtlasSprite sprite : sprites) {
                int atlas = minecraft.getTextureManager()
                    .getTexture(sprite.atlasLocation())
                    .getId();
                RenderSystem.bindTexture(atlas);
                int min = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
                int mag = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
                int wrapS = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S);
                int wrapT = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T);
                int levels = Math.min(
                    sprite.contents().byMipLevel.length,
                    Integer.SIZE - Integer.numberOfLeadingZeros(
                        Math.min(
                            sprite.contents()
                                .width(),
                            sprite.contents()
                                .height())));
                Region region = new Region(sprite, atlas, GL11.glGenTextures(), min, mag, wrapS, wrapT, levels);
                regions.add(region);
                RenderSystem.bindTexture(region.backup);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
                for (int level = 0; level < levels; level++) {
                    int width = sprite.contents()
                        .width() >> level,
                        height = sprite.contents()
                            .height() >> level;
                    GL11.glTexImage2D(
                        GL11.GL_TEXTURE_2D,
                        level,
                        GL11.GL_RGBA8,
                        width,
                        height,
                        0,
                        GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE,
                        (ByteBuffer) null);
                }
                // Allocate the full mip chain before attaching any level to a framebuffer.
                for (int level = 0; level < levels; level++) {
                    if ((sprite.contents()
                        .width() >> level) == 0
                        || (sprite.contents()
                            .height() >> level) == 0)
                        break;
                    copy(region, level, false);
                }
            }
            checkTransfer();
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                Throwable combined = combine(failure, cleanup);
                if (combined instanceof Error error) throw error;
                throw (RuntimeException) combined;
            }
            throw failure;
        }
    }

    void changing() {
        changed = true;
    }

    private void copy(Region region, int level, boolean restore) {
        int width = region.sprite.contents()
            .width() >> level,
            height = region.sprite.contents()
                .height() >> level;
        int x = region.sprite.getX() >> level, y = region.sprite.getY() >> level;
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL30.glFramebufferTexture2D(
            GL30.GL_READ_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            restore ? region.backup : region.atlas,
            level);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        requireComplete(GL30.GL_READ_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL30.glFramebufferTexture2D(
            GL30.GL_DRAW_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            restore ? region.atlas : region.backup,
            level);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        requireComplete(GL30.GL_DRAW_FRAMEBUFFER);
        GL30.glBlitFramebuffer(
            restore ? 0 : x,
            restore ? 0 : y,
            restore ? width : x + width,
            restore ? height : y + height,
            restore ? x : 0,
            restore ? y : 0,
            restore ? x + width : width,
            restore ? y + height : height,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST);
        checkTransfer();
    }

    private static void requireComplete(int target) {
        int status = GL30.glCheckFramebufferStatus(target);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException(
            "Could not attach icon animation atlas rectangle: target=" + target + ", status=" + status);
    }

    static void checkTransfer() {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR)
            throw new IllegalStateException("Icon animation atlas transfer failed: " + error);
    }

    @Override
    public void close() {
        Throwable failure = null;
        if (changed) {
            try {
                PixelReadback.discardRenderingErrors();
            } catch (RuntimeException | Error problem) {
                failure = combine(failure, problem);
            }
            try {
                RenderSystem.disableScissor();
                GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            } catch (RuntimeException | Error problem) {
                failure = combine(failure, problem);
            }
            for (Region region : regions) {
                for (int level = 0; level < region.levels; level++) {
                    if ((region.sprite.contents()
                        .width() >> level) == 0
                        || (region.sprite.contents()
                            .height() >> level) == 0)
                        break;
                    try {
                        copy(region, level, true);
                    } catch (RuntimeException | Error problem) {
                        failure = combine(failure, problem);
                    }
                }
                try {
                    RenderSystem.bindTexture(region.atlas);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, region.min);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, region.mag);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, region.wrapS);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, region.wrapT);
                    checkTransfer();
                } catch (RuntimeException | Error problem) {
                    failure = combine(failure, problem);
                }
            }
        }
        for (Region region : regions) {
            try {
                GL11.glDeleteTextures(region.backup);
            } catch (RuntimeException | Error problem) {
                failure = combine(failure, problem);
            }
        }
        try {
            GL30.glDeleteFramebuffers(readFramebuffer);
        } catch (RuntimeException | Error problem) {
            failure = combine(failure, problem);
        }
        try {
            GL30.glDeleteFramebuffers(drawFramebuffer);
        } catch (RuntimeException | Error problem) {
            failure = combine(failure, problem);
        }
        try {
            if (srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
            else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        } catch (RuntimeException | Error problem) {
            failure = combine(failure, problem);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }

    private static Throwable combine(Throwable current, Throwable next) {
        if (current == null) return next;
        if (current == next) return current;
        if (next instanceof VirtualMachineError || next instanceof ThreadDeath) {
            next.addSuppressed(current);
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private record Region(TextureAtlasSprite sprite, int atlas, int backup, int min, int mag, int wrapS, int wrapT,
        int levels) {}
}
