package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;

import pl.kuba6000.ae2webintegration.core.icons.export.IIconAnimation;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCapture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.CaptureKind;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Client-thread GUI capture; native model overrides and Forge custom item renderers remain authoritative. */
final class ModernIconRenderer implements IIconCapture<IconCandidate> {

    static final int ICON_SIZE = 64;
    private static final int GUI_SIZE = 16;
    private static final int GUI_DEPTH = 1000;
    private static final int INITIAL_VERTEX_BYTES = 256;
    private final Minecraft minecraft;
    private final PixelReadback readback;
    private final RenderState state = new RenderState();
    private final RenderState animationState = new RenderState();
    private final ByteBufferBuilder vertices;
    private final SequencedMap<RenderType, ByteBufferBuilder> glintBuffers = new LinkedHashMap<>();
    private final Matrix4f projection = new Matrix4f().setOrtho(0, GUI_SIZE, GUI_SIZE, 0, -GUI_DEPTH, GUI_DEPTH);
    private @Nullable TextureTarget target;
    private CaptureKind captureKind = CaptureKind.STATIC;
    private long captureTiming;
    private LongSupplier drawClock = System::nanoTime;
    private final Map<SpriteAnimation.Selection, Long> animationSelections = new EnumMap<>(
        SpriteAnimation.Selection.class);
    private long spriteAnimationPlans, truncatedAnimationPlans;
    private long snapshotNanos, snapshotCount, setupNanos, setupCount;
    private long drawNanos, drawCount, restoreNanos, restoreCount;

    ModernIconRenderer(@NotNull Minecraft minecraft, boolean allowPbo) {
        this.minecraft = minecraft;
        readback = new PixelReadback(allowPbo);
        vertices = new ByteBufferBuilder(INITIAL_VERTEX_BYTES);
        try {
            // Foil VertexMultiConsumer needs distinct buffers alive simultaneously, just as native RenderBuffers does.
            for (RenderType type : List.of(
                RenderType.armorEntityGlint(),
                RenderType.glint(),
                RenderType.glintTranslucent(),
                RenderType.entityGlint(),
                RenderType.entityGlintDirect())) {
                glintBuffers.put(type, new ByteBufferBuilder(INITIAL_VERTEX_BYTES));
            }
        } catch (RuntimeException | Error failure) {
            vertices.close();
            for (ByteBufferBuilder builder : glintBuffers.values()) builder.close();
            throw failure;
        }
    }

    void item(@NotNull ItemStack item, @NotNull StableKey key, @NotNull List<Capture> captures) throws RenderFailure {
        capture(key, captures, graphics -> graphics.renderItem(item, 0, 0));
    }

    void fluid(@NotNull FluidStack stack, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws RenderFailure {
        capture(key, captures, graphics -> {
            IClientFluidTypeExtensions attributes = IClientFluidTypeExtensions.of(stack.getFluid());
            ResourceLocation still = attributes.getStillTexture(stack);
            if (still == null) throw new IllegalArgumentException("Fluid has no inventory sprite");
            TextureAtlasSprite sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(still);
            int tint = attributes.getTintColor(stack);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            graphics.blit(
                0,
                0,
                0,
                GUI_SIZE,
                GUI_SIZE,
                sprite,
                (tint >> 16 & 255) / 255.0F,
                (tint >> 8 & 255) / 255.0F,
                (tint & 255) / 255.0F,
                (tint >>> 24) / 255.0F);
        });
    }

    private void capture(@NotNull StableKey key, @NotNull List<Capture> captures, @NotNull Consumer<GuiGraphics> draw)
        throws RenderFailure {
        RenderSystem.assertOnRenderThread();
        // Export runs at the exclusive RenderFrame.Pre boundary, before ordinary GUI/world tessellation.
        ByteBufferBuilder immediate = Tesselator.getInstance().buffer;
        Throwable renderFailure = null;
        long started = System.nanoTime();
        boolean snapshotComplete = false;
        long restoreStarted = 0;
        boolean panoramic = minecraft.gameRenderer.isPanoramicMode();
        try (RenderState ignored = state.snapshot()) {
            snapshotNanos += System.nanoTime() - started;
            snapshotCount++;
            snapshotComplete = true;
            try {
                setup();
                // Fabulous world output targets must not redirect GUI layers away from this transparent target.
                minecraft.gameRenderer.setPanoramicMode(true);
                MultiBufferSource.BufferSource buffers = MultiBufferSource.immediateWithBuffers(glintBuffers, vertices);
                GuiGraphics graphics = new GuiGraphics(minecraft, buffers);
                started = System.nanoTime();
                try {
                    if (captureKind == CaptureKind.SAMPLE) captureTiming = drawClock.getAsLong();
                    draw.accept(graphics);
                    graphics.flush();
                } catch (Throwable failure) {
                    Throwable cause = failure instanceof ReportedException ? failure.getCause() : failure;
                    // Retain fatal draw failures too: a failed native reallocation can make subsequent cleanup throw.
                    renderFailure = cause;
                    if (cause instanceof VirtualMachineError fatal) throw fatal;
                    if (cause instanceof ThreadDeath fatal) throw fatal;
                } finally {
                    drawNanos += System.nanoTime() - started;
                    drawCount++;
                    // Discard unfinished owned geometry after a custom renderer fails; never draw it into the next
                    // icon.
                    discardBuffers(immediate);
                }
                if (renderFailure == null) {
                    readback.submit(key, captures, captureKind, captureTiming);
                    return;
                }
            } finally {
                minecraft.gameRenderer.setPanoramicMode(panoramic);
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
                snapshotNanos += System.nanoTime() - started;
                snapshotCount++;
            }
        }
        throw new RenderFailure(renderFailure);
    }

    private void discardBuffers(@NotNull ByteBufferBuilder immediate) {
        Throwable failure = discard(vertices, null);
        failure = discard(immediate, failure);
        for (ByteBufferBuilder builder : glintBuffers.values()) failure = discard(builder, failure);
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private static @Nullable Throwable discard(@NotNull ByteBufferBuilder builder, @Nullable Throwable priorFailure) {
        try {
            discard(builder);
        } catch (RuntimeException | Error failure) {
            if (priorFailure == null) return failure;
            if (priorFailure == failure) return priorFailure;
            if (!(priorFailure instanceof VirtualMachineError) && !(priorFailure instanceof ThreadDeath)
                && (failure instanceof VirtualMachineError || failure instanceof ThreadDeath)) {
                failure.addSuppressed(priorFailure);
                return failure;
            }
            priorFailure.addSuppressed(failure);
        }
        return priorFailure;
    }

    private static void discard(@NotNull ByteBufferBuilder builder) {
        // discard() alone keeps unbuilt bytes. Materialize and close their raw result, without drawing partial
        // geometry.
        try (ByteBufferBuilder.Result ignored = builder.build()) {
            builder.discard();
        }
    }

    private void setup() {
        long started = System.nanoTime();
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            if (target == null) target = new TextureTarget(ICON_SIZE, ICON_SIZE, true, Minecraft.ON_OSX);
            RenderSystem.disableScissor();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            target.setClearColor(0, 0, 0, 0);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.getModelViewStack()
                .identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.resetTextureMatrix();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableCull();
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glFrontFace(GL11.GL_CCW);
            RenderSystem.disablePolygonOffset();
            RenderSystem.disableColorLogicOp();
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            Lighting.setupFor3DItems();
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
    public @Nullable IIconAnimation animation(@NotNull IconCandidate candidate, @NotNull StableKey key)
        throws RenderFailure {
        RenderFailure preparationFailure = null;
        IIconAnimation result = null;
        try (RenderState ignored = state.snapshot()) {
            try {
                result = SpriteAnimation.create(minecraft, this, candidate, key);
            } catch (RenderFailure failure) {
                preparationFailure = failure;
            }
        } catch (RuntimeException | Error failure) {
            if (preparationFailure != null) failure.addSuppressed(preparationFailure);
            throw failure;
        }
        if (preparationFailure != null) throw preparationFailure;
        return result;
    }

    void animated(@NotNull IconCandidate candidate, @NotNull StableKey key, @NotNull List<Capture> captures,
        @NotNull CaptureKind kind, long timing, @NotNull LongSupplier clock) throws RenderFailure {
        captureKind = kind;
        captureTiming = timing;
        drawClock = clock;
        try {
            candidate.render(this, key, captures);
        } finally {
            captureKind = CaptureKind.STATIC;
            drawClock = System::nanoTime;
        }
    }

    @Override
    public void drain(@NotNull List<Capture> captures) {
        readback.drain(captures);
    }

    @NotNull
    RenderState controlledState() {
        return animationState.snapshot();
    }

    void animationSelection(SpriteAnimation.Selection selection) {
        animationSelections.merge(selection, 1L, Long::sum);
    }

    void animationPlan(boolean truncated) {
        spriteAnimationPlans++;
        if (truncated) truncatedAnimationPlans++;
    }

    @Override
    public void statistics(@NotNull Map<String, Long> counts) {
        for (SpriteAnimation.Selection selection : SpriteAnimation.Selection.values()) {
            counts.put(selection.statistic, animationSelections.getOrDefault(selection, 0L));
        }
        counts.put("spriteAnimationPlans", spriteAnimationPlans);
        counts.put("truncatedAnimationPlans", truncatedAnimationPlans);
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

    @Override
    public void close() {
        try {
            readback.close();
        } finally {
            try {
                if (target != null) {
                    try (RenderState ignored = state.snapshot()) {
                        target.destroyBuffers();
                    } finally {
                        target = null;
                    }
                }
            } finally {
                vertices.close();
                for (ByteBufferBuilder builder : glintBuffers.values()) builder.close();
            }
        }
    }
}
