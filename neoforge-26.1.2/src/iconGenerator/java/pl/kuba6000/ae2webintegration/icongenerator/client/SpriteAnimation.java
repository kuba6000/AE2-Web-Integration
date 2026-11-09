package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.export.IIconAnimation;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCapture.RenderFailure;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.CaptureKind;
import pl.kuba6000.ae2webintegration.core.icons.export.SpriteTimeline;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Native GUI sprite selection and controlled atlas frame replay. */
final class SpriteAnimation implements IIconAnimation {

    enum Selection {

        STATIC("staticAnimationCandidates"),
        CUSTOM_RENDERER("sampledCustomAnimationPlans"),
        SPRITE_IMPLEMENTATION("unsupportedSpriteAnimations");

        final String statistic;

        Selection(String statistic) {
            this.statistic = statistic;
        }
    }

    private final Minecraft minecraft;
    private final ModernIconRenderer renderer;
    private final IconCandidate candidate;
    private final StableKey key;
    private final List<TextureAtlasSprite> sprites;
    private final @Nullable SpriteTimeline timeline;
    private int step;

    private SpriteAnimation(Minecraft minecraft, ModernIconRenderer renderer, IconCandidate candidate, StableKey key,
        List<TextureAtlasSprite> sprites, @Nullable SpriteTimeline timeline) {
        this.minecraft = minecraft;
        this.renderer = renderer;
        this.candidate = candidate;
        this.key = key;
        this.sprites = sprites;
        this.timeline = timeline;
    }

    static @Nullable IIconAnimation create(Minecraft minecraft, ModernIconRenderer renderer, IconCandidate candidate,
        StableKey key) throws RenderFailure {
        Set<TextureAtlasSprite> used = new LinkedHashSet<>();
        boolean sampled;
        try {
            ItemStack item = candidate.animationItem();
            if (!item.isEmpty()) sampled = collectItem(minecraft, item, used);
            else {
                FluidStack fluid = candidate.animationFluid();
                used.add(
                    ModernIconRenderer.fluidModel(minecraft, fluid)
                        .stillMaterial()
                        .sprite());
                sampled = false;
            }
            if (sampled) {
                renderer.animationSelection(Selection.CUSTOM_RENDERER);
                return sampled(minecraft, renderer, candidate, key);
            }
            List<TextureAtlasSprite> sprites = used.stream()
                .filter(sprite -> sprite.contents().animatedTexture != null)
                .toList();
            if (sprites.isEmpty()) {
                renderer.animationSelection(Selection.STATIC);
                return null;
            }
            for (TextureAtlasSprite sprite : sprites) {
                if (sprite.getClass() != TextureAtlasSprite.class || sprite.contents()
                    .getClass() != SpriteContents.class) {
                    renderer.animationSelection(Selection.SPRITE_IMPLEMENTATION);
                    return null;
                }
            }
            List<SpriteTimeline.Track> tracks = new ArrayList<>();
            for (TextureAtlasSprite sprite : sprites) {
                SpriteContents.AnimatedTexture animation = sprite.contents().animatedTexture;
                tracks.add(
                    new SpriteTimeline.Track(
                        animation.frames.stream()
                            .mapToInt(SpriteContents.FrameInfo::time)
                            .toArray(),
                        animation.frames.stream()
                            .mapToInt(SpriteContents.FrameInfo::index)
                            .toArray(),
                        animation.interpolateFrames));
            }
            SpriteTimeline timeline = SpriteTimeline.create(tracks);
            renderer.animationPlan(timeline.reduced());
            return new SpriteAnimation(minecraft, renderer, candidate, key, sprites, timeline);
        } catch (RuntimeException | LinkageError failure) {
            throw ModernIconRenderer.animationFailure(failure);
        }
    }

    static @NotNull IIconAnimation sampled(Minecraft minecraft, ModernIconRenderer renderer, IconCandidate candidate,
        StableKey key) {
        return new SpriteAnimation(minecraft, renderer, candidate, key, List.of(), null);
    }

    private static boolean collectItem(Minecraft minecraft, ItemStack item, Set<TextureAtlasSprite> sprites) {
        ItemStackRenderState state = new ItemStackRenderState();
        minecraft.getItemModelResolver()
            .updateForTopItem(state, item, ItemDisplayContext.GUI, minecraft.level, minecraft.player, 0);
        boolean sampled = item.hasFoil();
        for (int index = 0; index < state.activeLayerCount; index++) {
            ItemStackRenderState.LayerRenderState layer = state.layers[index];
            sampled |= layer.specialRenderer != null;
            layer.prepareQuadList()
                .forEach(
                    quad -> sprites.add(
                        quad.materialInfo()
                            .sprite()));
        }
        return sampled;
    }

    @Override
    public @NotNull Kind kind() {
        return timeline == null ? Kind.SAMPLED : Kind.SPRITE;
    }

    @Override
    public boolean capture(@NotNull List<Capture> captures, @NotNull LongSupplier drawClock) throws RenderFailure {
        if (timeline == null) {
            renderer.animated(candidate, key, captures, CaptureKind.SAMPLE, 0, drawClock);
            return true;
        }
        SpriteTimeline.Frame frame = timeline.frames()
            .get(step++);
        RenderFailure drawFailure = null;
        try (RenderState ignored = renderer.controlledState();
            AtlasAnimation atlas = new AtlasAnimation(minecraft, sprites)) {
            try {
                atlas.animate(frame.tick());
                renderer.animated(candidate, key, captures, CaptureKind.FRAME, frame.durationMillis(), drawClock);
            } catch (RenderFailure failure) {
                drawFailure = failure;
            }
        } catch (RuntimeException | Error failure) {
            if (drawFailure != null) failure.addSuppressed(drawFailure);
            throw failure;
        }
        if (drawFailure != null) throw drawFailure;
        return step < timeline.frames()
            .size();
    }

    @Override
    public void close() {}
}
