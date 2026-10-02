package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.ByteBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.ReportedException;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;

/** Client-thread inventory capture. Each call restores the caller's GL state, including on failure. */
final class LegacyIconRenderer implements AutoCloseable {

    static final int ICON_SIZE = 64;
    private static final int GUI_SIZE = 16;
    private static final int GUI_DEPTH = 1000;
    private static final int FULL_BRIGHTNESS = 240;
    private static final int GL_QUERY_BUFFER_SIZE = 16;
    private final Minecraft minecraft;
    private final RenderItem renderItem = new AlphaPreservingRenderItem();
    private final ByteBuffer rgba = BufferUtils.createByteBuffer(ICON_SIZE * ICON_SIZE * Integer.BYTES);
    private @Nullable Framebuffer target;

    LegacyIconRenderer(@NotNull Minecraft minecraft) {
        this.minecraft = minecraft;
        renderItem.renderWithColor = true;
    }

    int @NotNull [] item(@NotNull ItemStack stack, int size) throws RenderFailure {
        return capture(size, () -> {
            RenderHelper.enableGUIStandardItemLighting();
            // GuiContainer enables this for RenderItem's scaled 3D block normals.
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            try {
                renderItem
                    .renderItemAndEffectIntoGUI(minecraft.fontRenderer, minecraft.getTextureManager(), stack, 0, 0);
            } finally {
                // Native RenderItem increments this before invoking third-party renderers.
                renderItem.zLevel = 0;
            }
        });
    }

    int @NotNull [] fluid(@NotNull FluidStack stack, int size) throws RenderFailure {
        return capture(size, () -> {
            IIcon icon = stack.getFluid()
                .getIcon(stack);
            if (icon == null) throw new IllegalArgumentException("Fluid has no inventory sprite");
            int color = stack.getFluid()
                .getColor(stack);
            minecraft.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            GL11.glDisable(GL11.GL_LIGHTING);
            // One sprite needs no composition. Write straight RGB and texture alpha directly to the PNG target.
            GL11.glDisable(GL11.GL_BLEND);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            // Forge 1.7.10 fluid colors are RGB; the absent high byte is not transparency.
            tessellator.setColorOpaque_I(color);
            tessellator.addVertexWithUV(0, GUI_SIZE, 0, icon.getMinU(), icon.getMaxV());
            tessellator.addVertexWithUV(GUI_SIZE, GUI_SIZE, 0, icon.getMaxU(), icon.getMaxV());
            tessellator.addVertexWithUV(GUI_SIZE, 0, 0, icon.getMaxU(), icon.getMinV());
            tessellator.addVertexWithUV(0, 0, 0, icon.getMinU(), icon.getMinV());
            tessellator.draw();
        });
    }

    private int @NotNull [] capture(int size, @NotNull Runnable draw) throws RenderFailure {
        if (!OpenGlHelper.isFramebufferEnabled())
            throw new IllegalStateException("Framebuffer rendering is unavailable");
        Throwable renderFailure = null;
        try (TessellatorState ignoredTessellator = new TessellatorState(); RenderState ignored = new RenderState()) {
            Framebuffer framebuffer = framebuffer(size);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            framebuffer.setFramebufferColor(0, 0, 0, 0);
            framebuffer.framebufferClear();
            framebuffer.bindFramebuffer(true);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, GUI_SIZE, GUI_SIZE, 0, -GUI_DEPTH, GUI_DEPTH);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, FULL_BRIGHTNESS, FULL_BRIGHTNESS);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColor4f(1, 1, 1, 1);
            try {
                draw.run();
            } catch (Throwable failure) {
                // RenderItem wraps even fatal renderer errors in a native crash report.
                Throwable cause = failure instanceof ReportedException ? failure.getCause() : failure;
                if (cause instanceof VirtualMachineError fatal) throw fatal;
                if (cause instanceof ThreadDeath fatal) throw fatal;
                renderFailure = failure;
            }
            if (renderFailure == null) return readPixels(size);
        } catch (RuntimeException | Error failure) {
            if (renderFailure != null) failure.addSuppressed(renderFailure);
            throw failure;
        }
        // Only a draw failure followed by successful state restoration is safe to skip.
        throw new RenderFailure(renderFailure);
    }

    static final class RenderFailure extends Exception {

        final Throwable failure;

        private RenderFailure(@NotNull Throwable failure) {
            super("Native icon renderer failed", failure);
            this.failure = failure instanceof ReportedException ? failure.getCause() : failure;
        }
    }

    @SuppressWarnings("PMD.AvoidMagicNumbers") // GL RGBA bytes become packed Java ARGB.
    private int @NotNull [] readPixels(int size) {
        rgba.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glReadPixels(0, 0, size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int red = rgba.get() & 255;
                int green = rgba.get() & 255;
                int blue = rgba.get() & 255;
                int alpha = rgba.get() & 255;
                pixels[(size - y - 1) * size + x] = alpha == 0 ? 0 : alpha << 24 | red << 16 | green << 8 | blue;
            }
        }
        return pixels;
    }

    private @NotNull Framebuffer framebuffer(int size) {
        if (size != ICON_SIZE) throw new IllegalArgumentException("Unsupported icon image size");
        if (target == null) target = new Framebuffer(size, size, true);
        return target;
    }

    @Override
    public void close() {
        if (target == null) return;
        try (RenderState ignored = new RenderState()) {
            target.deleteFramebuffer();
        } finally {
            target = null;
        }
    }

    private static final class AlphaPreservingRenderItem extends RenderItem {

        @Override
        @SuppressWarnings("PMD.AvoidMagicNumbers") // Four positional RGBA mask channels.
        public void renderEffect(TextureManager manager, int x, int y) {
            // LWJGL 2 requires room for sixteen values even for the four-component write mask.
            ByteBuffer mask = BufferUtils.createByteBuffer(GL_QUERY_BUFFER_SIZE);
            GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, mask);
            GL11.glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, false);
            try {
                // Native glint uses ZERO/ZERO alpha blending, erasing the item on transparent targets.
                super.renderEffect(manager, x, y);
            } finally {
                GL11.glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
            }
        }
    }

    private static final class TessellatorState implements AutoCloseable {

        private final Tessellator tessellator = TessellatorManager.get();
        private final double x = tessellator.xOffset;
        private final double y = tessellator.yOffset;
        private final double z = tessellator.zOffset;

        private TessellatorState() {
            if (tessellator.isDrawing) throw new IllegalStateException("Cannot capture during an active tessellation");
            tessellator.setTranslation(0, 0, 0);
        }

        @Override
        public void close() {
            TessellatorManager.discardTessellator(tessellator);
            tessellator.setTranslation(x, y, z);
        }
    }
}
