package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ReportedException;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;

import pl.kuba6000.ae2webintegration.core.icons.export.IIconCapture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Client-thread inventory capture. Each call restores the caller's GL state, including on failure. */
final class LegacyIconRenderer implements IIconCapture<IconCandidate> {

    static final int ICON_SIZE = 64;
    private static final int GUI_SIZE = 16;
    private static final int GUI_DEPTH = 1000;
    private static final int FULL_BRIGHTNESS = 240;
    private static final int GL_QUERY_BUFFER_SIZE = 16;
    private final Minecraft minecraft;
    private final RenderItem renderItem;
    private final PixelReadback readback;
    private final RenderState state = new RenderState();
    private @Nullable Framebuffer target;
    private long snapshotNanos;
    private long snapshotCount;
    private long setupNanos;
    private long setupCount;
    private long drawNanos;
    private long drawCount;
    private long restoreNanos;
    private long restoreCount;

    LegacyIconRenderer(@NotNull Minecraft minecraft, boolean allowPbo) {
        this.minecraft = minecraft;
        readback = new PixelReadback(allowPbo);
        renderItem = new AlphaPreservingRenderItem(minecraft);
    }

    void item(@NotNull ItemStack stack, @NotNull StableKey key, @NotNull List<Capture> captures) throws RenderFailure {
        capture(ICON_SIZE, key, captures, () -> {
            RenderHelper.enableGUIStandardItemLighting();
            // GuiContainer enables this for RenderItem's scaled 3D block normals.
            GlStateManager.enableRescaleNormal();
            try {
                renderItem.renderItemAndEffectIntoGUI(minecraft.player, stack, 0, 0);
            } finally {
                // Native RenderItem increments this before invoking third-party renderers.
                renderItem.zLevel = 0;
            }
        });
    }

    void fluid(@NotNull FluidStack stack, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws RenderFailure {
        capture(ICON_SIZE, key, captures, () -> {
            ResourceLocation still = stack.getFluid()
                .getStill(stack);
            if (still == null) throw new IllegalArgumentException("Fluid has no inventory sprite");
            TextureAtlasSprite icon = minecraft.getTextureMapBlocks()
                .getAtlasSprite(still.toString());
            if (icon == minecraft.getTextureMapBlocks()
                .getMissingSprite())
                throw new IllegalArgumentException("Fluid inventory sprite is missing from the atlas");
            int color = stack.getFluid()
                .getColor(stack);
            minecraft.getTextureManager()
                .bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            captureBlend();
            GlStateManager.color(
                (color >> 16 & 255) / 255.0F,
                (color >> 8 & 255) / 255.0F,
                (color & 255) / 255.0F,
                (color >>> 24) / 255.0F);
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder vertices = tessellator.getBuffer();
            vertices.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            vertices.pos(0, GUI_SIZE, 0)
                .tex(icon.getMinU(), icon.getMaxV())
                .endVertex();
            vertices.pos(GUI_SIZE, GUI_SIZE, 0)
                .tex(icon.getMaxU(), icon.getMaxV())
                .endVertex();
            vertices.pos(GUI_SIZE, 0, 0)
                .tex(icon.getMaxU(), icon.getMinV())
                .endVertex();
            vertices.pos(0, 0, 0)
                .tex(icon.getMinU(), icon.getMinV())
                .endVertex();
            tessellator.draw();
        });
    }

    private void capture(int size, @NotNull StableKey key, @NotNull List<Capture> captures, @NotNull Runnable draw)
        throws RenderFailure {
        if (!OpenGlHelper.isFramebufferEnabled())
            throw new IllegalStateException("Framebuffer rendering is unavailable");
        Throwable renderFailure = null;
        long snapshotStarted = System.nanoTime();
        boolean snapshotComplete = false;
        long restoreStarted = 0;
        try (TessellatorState ignoredTessellator = new TessellatorState(); RenderState ignored = state.snapshot()) {
            snapshotNanos += System.nanoTime() - snapshotStarted;
            snapshotCount++;
            snapshotComplete = true;
            try {
                setup(size);
                long drawStarted = System.nanoTime();
                try {
                    draw.run();
                } catch (Throwable failure) {
                    // RenderItem wraps even fatal renderer errors in a native crash report.
                    Throwable cause = failure instanceof ReportedException ? failure.getCause() : failure;
                    renderFailure = cause;
                    if (cause instanceof VirtualMachineError fatal) throw fatal;
                    if (cause instanceof ThreadDeath fatal) throw fatal;
                } finally {
                    drawNanos += System.nanoTime() - drawStarted;
                    drawCount++;
                }
                if (renderFailure == null) {
                    readback.submit(key, captures);
                    return;
                }
            } finally {
                restoreStarted = System.nanoTime();
            }
        } catch (RuntimeException | Error failure) {
            if (renderFailure != null && renderFailure != failure) {
                if (renderFailure instanceof VirtualMachineError fatal) {
                    fatal.addSuppressed(failure);
                    throw fatal;
                }
                if (renderFailure instanceof ThreadDeath fatal) {
                    fatal.addSuppressed(failure);
                    throw fatal;
                }
                failure.addSuppressed(renderFailure);
            }
            throw failure;
        } finally {
            if (snapshotComplete) {
                restoreNanos += System.nanoTime() - restoreStarted;
                restoreCount++;
            } else {
                // A failed resource initializer can also close already-acquired Tessellator state.
                snapshotNanos += System.nanoTime() - snapshotStarted;
                snapshotCount++;
            }
        }
        // Only a draw failure followed by successful state restoration is safe to skip.
        throw new RenderFailure(renderFailure);
    }

    private static void captureBlend() {
        // Native blendFunc updates RGB cache fields only, although GL also changes its alpha factors.
        // Set the driver explicitly before reconciling the separate-alpha cache.
        OpenGlHelper
            .glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void setup(int size) {
        long started = System.nanoTime();
        try {
            if (PixelReadback.supportsPackBinding()) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            }
            GL20.glUseProgram(0);
            Framebuffer framebuffer = framebuffer(size);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(true);
            framebuffer.setFramebufferColor(0, 0, 0, 0);
            framebuffer.framebufferClear();
            framebuffer.bindFramebuffer(true);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, GUI_SIZE, GUI_SIZE, 0, -GUI_DEPTH, GUI_DEPTH);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.disableTexture2D();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, FULL_BRIGHTNESS, FULL_BRIGHTNESS);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.disableFog();
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0);
            GlStateManager.disableCull();
            GlStateManager.color(1, 1, 1, 1);
        } finally {
            setupNanos += System.nanoTime() - started;
            setupCount++;
        }
    }

    @Override
    public void capture(@NotNull IconCandidate candidate, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws RenderFailure {
        candidate.render(this, key, captures);
    }

    @Override
    public void drain(@NotNull List<Capture> captures) {
        readback.drain(captures);
    }

    /** Per-attempt wall times include pauses and deferred GPU work charged to the synchronizing call. */
    @Override
    public void statistics(@NotNull Map<String, Long> counts) {
        counts.put("renderSnapshotNanos", snapshotNanos);
        counts.put("renderSnapshotCount", snapshotCount);
        counts.put("renderSetupNanos", setupNanos);
        counts.put("renderSetupCount", setupCount);
        counts.put("renderDrawNanos", drawNanos);
        counts.put("renderDrawCount", drawCount);
        counts.put("renderRestoreNanos", restoreNanos);
        counts.put("renderRestoreCount", restoreCount);
        readback.statistics(counts);
    }

    private @NotNull Framebuffer framebuffer(int size) {
        if (size != ICON_SIZE) throw new IllegalArgumentException("Unsupported icon image size");
        if (target == null) target = new Framebuffer(size, size, true);
        return target;
    }

    @Override
    public void close() {
        try {
            readback.close();
        } finally {
            if (target != null) {
                try (RenderState ignored = state.snapshot()) {
                    target.deleteFramebuffer();
                } finally {
                    target = null;
                }
            }
        }
    }

    private static final class AlphaPreservingRenderItem extends RenderItem {

        private final Minecraft minecraft;
        private final ByteBuffer mask = BufferUtils.createByteBuffer(GL_QUERY_BUFFER_SIZE);

        private AlphaPreservingRenderItem(@NotNull Minecraft minecraft) {
            super(
                minecraft.getTextureManager(),
                minecraft.getRenderItem()
                    .getItemModelMesher()
                    .getModelManager(),
                minecraft.getItemColors());
            this.minecraft = minecraft;
        }

        @Override
        public @NotNull IBakedModel getItemModelWithOverrides(@NotNull ItemStack stack, @Nullable World world,
            @Nullable EntityLivingBase entity) {
            // Use the actual mod-populated mesher/overrides, not this helper renderer's vanilla registration table.
            return minecraft.getRenderItem()
                .getItemModelWithOverrides(stack, world, entity);
        }

        @Override
        public void renderItem(@NotNull ItemStack stack, @NotNull IBakedModel model) {
            if (stack.isEmpty()) return;
            // Keep native dispatch separate: release glint bytecode invokespecial bypasses a model-method override.
            captureBlend();
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(-0.5F, -0.5F, -0.5F);
                if (model.isBuiltInRenderer()) {
                    GlStateManager.color(1, 1, 1, 1);
                    GlStateManager.enableRescaleNormal();
                    stack.getItem()
                        .getTileEntityItemStackRenderer()
                        .renderByItem(stack);
                } else {
                    super.renderModel(model, stack);
                    if (stack.hasEffect()) renderGlint(model);
                }
            } finally {
                GlStateManager.popMatrix();
            }
        }

        private void renderGlint(@NotNull IBakedModel model) {
            mask.clear();
            GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, mask);
            GlStateManager.colorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, false);
            try {
                // Execute both native glint passes, texture transforms and RGB blending without changing base alpha.
                super.renderEffect(model);
            } finally {
                GlStateManager.colorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
            }
        }

        @Override
        protected void renderItemModelIntoGUI(@NotNull ItemStack stack, int x, int y, @NotNull IBakedModel model) {
            try {
                super.renderItemModelIntoGUI(stack, x, y, model);
            } catch (RuntimeException | Error failure) {
                // Native code restores atlas filtering only after a successful draw.
                try {
                    minecraft.getTextureManager()
                        .getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE)
                        .restoreLastBlurMipmap();
                } catch (RuntimeException | Error cleanup) {
                    if (!(failure instanceof VirtualMachineError) && !(failure instanceof ThreadDeath)
                        && (cleanup instanceof VirtualMachineError || cleanup instanceof ThreadDeath)) {
                        cleanup.addSuppressed(failure);
                        throw cleanup;
                    }
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
    }

    private static final class TessellatorState implements AutoCloseable {

        private final BufferBuilder buffer = Tessellator.getInstance()
            .getBuffer();
        private final double x = buffer.xOffset;
        private final double y = buffer.yOffset;
        private final double z = buffer.zOffset;

        private TessellatorState() {
            if (buffer.isDrawing) throw new IllegalStateException("Cannot capture during active tessellation");
            buffer.setTranslation(0, 0, 0);
        }

        @Override
        public void close() {
            try {
                if (buffer.isDrawing) buffer.finishDrawing();
                buffer.reset();
            } finally {
                buffer.setTranslation(x, y, z);
            }
        }
    }
}
