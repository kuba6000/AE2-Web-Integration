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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;

import pl.kuba6000.ae2webintegration.core.icons.export.IIconAnimation;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCapture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.CaptureKind;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Owns a native extraction/submission pipeline, isolated from the frame's GUI and geometry. */
final class ModernIconRenderer implements IIconCapture<IconCandidate> {

    static final int ICON_SIZE = 64;
    private static final int GUI_SIZE = 16;
    private static final int INITIAL_VERTEX_BYTES = 256;
    private final Minecraft minecraft;
    private final PixelReadback readback;
    private final RenderState state = new RenderState();
    private final RenderState animationState = new RenderState();
    private Pipeline pipeline;
    private final TextureTarget target;
    private CaptureKind captureKind = CaptureKind.STATIC;
    private long captureTiming;
    private LongSupplier drawClock = System::nanoTime;
    private final Map<SpriteAnimation.Selection, Long> animationSelections = new EnumMap<>(
        SpriteAnimation.Selection.class);
    private long spriteAnimationPlans, reducedAnimationPlans;
    private long snapshotNanos, snapshotCount, setupNanos, setupCount;
    private long drawNanos, drawCount, restoreNanos, restoreCount;

    ModernIconRenderer(@NotNull Minecraft minecraft, boolean allowPbo) {
        this.minecraft = minecraft;
        readback = new PixelReadback(allowPbo);
        pipeline = new Pipeline(minecraft);
        try {
            target = new TextureTarget("ae2webicons", ICON_SIZE, ICON_SIZE, true);
        } catch (RuntimeException | Error failure) {
            try {
                pipeline.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void item(@NotNull ItemStack item, @NotNull StableKey key, @NotNull List<Capture> captures) throws RenderFailure {
        capture(key, captures, graphics -> graphics.item(item, 0, 0));
    }

    static FluidModel fluidModel(Minecraft minecraft, FluidStack stack) {
        return minecraft.getModelManager()
            .getFluidStateModelSet()
            .get(
                stack.getFluid()
                    .defaultFluidState());
    }

    void fluid(@NotNull FluidStack stack, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws RenderFailure {
        capture(key, captures, graphics -> {
            FluidModel model = fluidModel(minecraft, stack);
            var tintSource = model.fluidTintSource();
            int tint = tintSource == null ? 0xFFFFFFFF : tintSource.colorAsStack(stack);
            graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                model.stillMaterial()
                    .sprite(),
                0,
                0,
                GUI_SIZE,
                GUI_SIZE,
                tint);
        });
    }

    private void capture(StableKey key, List<Capture> captures, Consumer<GuiGraphicsExtractor> draw)
        throws RenderFailure {
        RenderSystem.assertOnRenderThread();
        long started = System.nanoTime();
        try (RenderState ignored = state.snapshot()) {
            snapshotNanos += System.nanoTime() - started;
            snapshotCount++;
            started = System.nanoTime();
            minecraft.mainRenderTarget = target;
            var window = minecraft.gameRenderer.getGameRenderState().windowRenderState;
            window.width = ICON_SIZE;
            window.height = ICON_SIZE;
            window.guiScale = ICON_SIZE / GUI_SIZE;
            RenderSystem.getDevice()
                .createCommandEncoder()
                .clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
            setupNanos += System.nanoTime() - started;
            setupCount++;
            started = System.nanoTime();
            try {
                if (captureKind == CaptureKind.SAMPLE) captureTiming = drawClock.getAsLong();
                draw.accept(new GuiGraphicsExtractor(minecraft, pipeline.guiState, -1, -1));
                pipeline.guiRenderer.render(RenderSystem.getShaderFog());
                pipeline.guiRenderer.endFrame();
            } catch (RuntimeException | Error failure) {
                // A failed custom submit may retain unfinished builders and meshes. Recreate our entire owned
                // submission pipeline so the next candidate cannot inherit them.
                RenderFailure renderFailure = animationFailure(failure);
                try {
                    pipeline.close();
                    pipeline = new Pipeline(minecraft);
                } catch (RuntimeException | Error cleanup) {
                    cleanup.addSuppressed(failure);
                    throw cleanup;
                }
                throw renderFailure;
            } finally {
                drawNanos += System.nanoTime() - started;
                drawCount++;
            }
            readback.submit(target.getColorTexture(), key, captures, captureKind, captureTiming);
            started = System.nanoTime();
        } finally {
            restoreNanos += System.nanoTime() - started;
            restoreCount++;
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

    @Override
    public @NotNull IIconAnimation sampledAnimation(@NotNull IconCandidate candidate, @NotNull StableKey key) {
        return SpriteAnimation.sampled(minecraft, this, candidate, key);
    }

    static @NotNull RenderFailure animationFailure(@NotNull Throwable failure) {
        Throwable cause = failure instanceof ReportedException || failure instanceof ExceptionInInitializerError
            ? failure.getCause()
            : failure;
        if (cause instanceof VirtualMachineError fatal) throw fatal;
        if (cause instanceof ThreadDeath fatal) throw fatal;
        return new RenderFailure(cause == null ? failure : cause);
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

    void animationPlan(boolean reduced) {
        spriteAnimationPlans++;
        if (reduced) reducedAnimationPlans++;
    }

    @Override
    public void statistics(@NotNull Map<String, Long> counts) {
        for (SpriteAnimation.Selection selection : SpriteAnimation.Selection.values()) {
            counts.put(selection.statistic, animationSelections.getOrDefault(selection, 0L));
        }
        counts.put("spriteAnimationPlans", spriteAnimationPlans);
        counts.put("reducedAnimationPlans", reducedAnimationPlans);
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
                pipeline.close();
            } finally {
                target.destroyBuffers();
            }
        }
    }

    private static final class Pipeline implements AutoCloseable {

        private final ByteBufferBuilder vertices = new ByteBufferBuilder(INITIAL_VERTEX_BYTES);
        private final SequencedMap<RenderType, ByteBufferBuilder> glintBuffers = new LinkedHashMap<>();
        final GuiRenderState guiState = new GuiRenderState();
        final GuiRenderer guiRenderer;
        final FeatureRenderDispatcher features;

        Pipeline(Minecraft minecraft) {
            try {
                for (RenderType type : List.of(
                    RenderTypes.armorEntityGlint(),
                    RenderTypes.glint(),
                    RenderTypes.glintTranslucent(),
                    RenderTypes.entityGlint())) {
                    glintBuffers.put(type, new ByteBufferBuilder(INITIAL_VERTEX_BYTES));
                }
                MultiBufferSource.BufferSource buffers = MultiBufferSource.immediateWithBuffers(glintBuffers, vertices);
                SubmitNodeStorage submits = new SubmitNodeStorage();
                features = new FeatureRenderDispatcher(
                    submits,
                    minecraft.getModelManager(),
                    buffers,
                    minecraft.getAtlasManager(),
                    minecraft.renderBuffers()
                        .outlineBufferSource(),
                    buffers,
                    minecraft.font,
                    minecraft.gameRenderer.getGameRenderState());
                guiRenderer = new GuiRenderer(guiState, buffers, submits, features, List.of());
            } catch (RuntimeException | Error failure) {
                vertices.close();
                for (ByteBufferBuilder builder : glintBuffers.values()) builder.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                guiRenderer.close();
            } finally {
                try {
                    features.close();
                } finally {
                    vertices.close();
                    for (ByteBufferBuilder builder : glintBuffers.values()) builder.close();
                }
            }
        }
    }
}
