package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.SimpleBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.BakedItemModel;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import pl.kuba6000.ae2webintegration.core.icons.export.IIconAnimation;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCapture.RenderFailure;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.CaptureKind;
import pl.kuba6000.ae2webintegration.core.icons.export.SpriteTimeline;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Native plans retain sprite metadata, never raw captured pixels or a modified shared atlas. */
final class NativeIconAnimation implements IIconAnimation {

    private static final boolean MODIFIED_ATLAS = FMLClientHandler.instance()
        .hasOptifine();
    private static final int MAX_TICKS = 1200;
    private static final int MAX_FRAMES = 256;
    private static final Field METADATA = ReflectionHelper
        .findField(TextureAtlasSprite.class, "animationMetadata", "field_110982_k");
    private final LegacyIconRenderer renderer;
    private final IconCandidate candidate;
    private final StableKey key;
    private final ResourceLocation atlas;
    private final List<NativeSprite> sprites;
    private final List<SpriteTimeline.Frame> frames;
    private int frame;

    private NativeIconAnimation(@NotNull LegacyIconRenderer renderer, @NotNull IconCandidate candidate,
        @NotNull StableKey key, @NotNull ResourceLocation atlas, @NotNull List<NativeSprite> sprites,
        @NotNull List<SpriteTimeline.Frame> frames) {
        this.renderer = renderer;
        this.candidate = candidate;
        this.key = key;
        this.atlas = atlas;
        this.sprites = sprites;
        this.frames = frames;
    }

    static @Nullable IIconAnimation item(@NotNull LegacyIconRenderer renderer, @NotNull IconCandidate candidate,
        @NotNull StableKey key, @NotNull ItemStack stack) {
        ResourceLocation atlas = TextureMap.LOCATION_BLOCKS_TEXTURE;
        IBakedModel model = renderer.capturedModel;
        if (model != null && model.isBuiltInRenderer()) {
            renderer.animationCustomRenderers.merge(
                stack.getItem()
                    .getTileEntityItemStackRenderer()
                    .getClass()
                    .getName(),
                1L,
                Long::sum);
            return sampled(renderer, candidate, key, atlas);
        }
        if (model == null || model.getClass() != SimpleBakedModel.class && model.getClass() != BakedItemModel.class
            && model.getClass() != BakedItemModel.BakedGuiItemModel.class) {
            renderer.animationUnsupportedShape++;
            return null;
        }
        List<TextureAtlasSprite> icons = new ArrayList<>();
        for (EnumFacing face : EnumFacing.values()) {
            for (BakedQuad quad : model.getQuads(null, face, 0)) icons.add(quad.getSprite());
        }
        for (BakedQuad quad : model.getQuads(null, null, 0)) icons.add(quad.getSprite());
        return plan(renderer, candidate, key, atlas, icons);
    }

    static @Nullable IIconAnimation fluid(@NotNull LegacyIconRenderer renderer, @NotNull IconCandidate candidate,
        @NotNull StableKey key, @NotNull FluidStack stack) {
        ResourceLocation still = stack.getFluid()
            .getStill(stack);
        if (still == null) return null;
        TextureAtlasSprite sprite = Minecraft.getMinecraft()
            .getTextureMapBlocks()
            .getAtlasSprite(still.toString());
        return plan(renderer, candidate, key, TextureMap.LOCATION_BLOCKS_TEXTURE, Collections.singletonList(sprite));
    }

    private static @NotNull IIconAnimation sampled(@NotNull LegacyIconRenderer renderer,
        @NotNull IconCandidate candidate, @NotNull StableKey key, @NotNull ResourceLocation atlas) {
        return new NativeIconAnimation(
            renderer,
            candidate,
            key,
            atlas,
            Collections.emptyList(),
            Collections.emptyList());
    }

    private static @Nullable IIconAnimation plan(@NotNull LegacyIconRenderer renderer, @NotNull IconCandidate candidate,
        @NotNull StableKey key, @NotNull ResourceLocation atlas, @NotNull List<TextureAtlasSprite> icons) {
        List<NativeSprite> timelines = new ArrayList<>();
        List<SpriteTimeline.Track> tracks = new ArrayList<>();
        long scratchBytes = 0;
        for (TextureAtlasSprite icon : icons) {
            if (icon == null || icon.getClass() != TextureAtlasSprite.class) {
                renderer.animationUnsupportedSprite++;
                return null;
            }
            TextureAtlasSprite sprite = icon;
            if (!sprite.hasAnimationMetadata()) continue;
            if (timelines.stream()
                .anyMatch(t -> t.sprite == sprite)) continue;
            NativeSprite timeline = timeline(sprite);
            if (timeline == null) {
                renderer.animationUnsupportedTimeline++;
                return null;
            }
            for (int[] pixels : timeline.pixels(0))
                if (pixels != null) scratchBytes += (long) pixels.length * Integer.BYTES;
            if (scratchBytes > AtlasFrameScope.MAX_SCRATCH_BYTES) {
                renderer.animationUnsupportedTimeline++;
                return null;
            }
            timelines.add(timeline);
            tracks.add(new SpriteTimeline.Track(timeline.durations, timeline.interpolated));
        }
        if (timelines.isEmpty()) return null;
        // Unknown shader atlas layouts cannot be safely controlled; keep the successful static icon.
        if (MODIFIED_ATLAS || GLContext.getCapabilities().OpenGL20 && GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0) {
            renderer.animationUnsupportedAtlas++;
            return null;
        }

        SpriteTimeline timing = SpriteTimeline.create(tracks);
        if (timing.truncated()) {
            renderer.animationUnsupportedTimeline++;
            return null;
        }
        return new NativeIconAnimation(renderer, candidate, key, atlas, timelines, timing.frames());
    }

    private static @Nullable NativeSprite timeline(@NotNull TextureAtlasSprite sprite) {
        try {
            AnimationMetadataSection metadata = (AnimationMetadataSection) METADATA.get(sprite);
            int count = metadata.getFrameCount();
            if (count < 1 || count > MAX_FRAMES) return null;
            int[] ends = new int[count];
            int[] durations = new int[count];
            int[] indexes = new int[count];
            long total = 0;
            for (int index = 0; index < count; index++) {
                int duration = metadata.getFrameTimeSingle(index);
                int image = metadata.getFrameIndex(index);
                if (duration <= 0 || image < 0 || image >= sprite.getFrameCount()) return null;
                total += duration;
                if (total > MAX_TICKS) return null;
                durations[index] = duration;
                ends[index] = (int) total;
                indexes[index] = image;
            }
            return new NativeSprite(sprite, indexes, ends, durations, (int) total, metadata.isInterpolate());
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read native sprite animation metadata", failure);
        }
    }

    @Override
    public @NotNull Kind kind() {
        return frames.isEmpty() ? Kind.SAMPLED : Kind.SPRITE;
    }

    @Override
    public boolean capture(@NotNull List<Capture> captures, @NotNull LongSupplier drawClock) throws RenderFailure {
        if (kind() == Kind.SAMPLED) {
            renderer.animationFrame(candidate, key, captures, CaptureKind.SAMPLE, 0, drawClock);
            return true;
        }
        int texture = Minecraft.getMinecraft()
            .getTextureManager()
            .getTexture(atlas)
            .getGlTextureId();
        RenderFailure drawFailure = null;
        try (AtlasFrameScope scope = new AtlasFrameScope(
            texture,
            sprites,
            renderer.atlasScratch,
            renderer.animationState)) {
            scope.select(
                frames.get(frame)
                    .tick());
            try {
                renderer.animationFrame(
                    candidate,
                    key,
                    captures,
                    CaptureKind.FRAME,
                    frames.get(frame)
                        .durationMillis(),
                    drawClock);
            } catch (RenderFailure failure) {
                drawFailure = failure;
            }
        } catch (RuntimeException | Error failure) {
            if (drawFailure != null) failure.addSuppressed(drawFailure);
            throw failure;
        }
        if (drawFailure != null) throw drawFailure;
        return ++frame < frames.size();
    }

    @Override
    public void close() {}

    static final class NativeSprite {

        final TextureAtlasSprite sprite;
        final int[] indexes;
        final int[] ends;
        final int cycle;
        final int[] durations;
        final boolean interpolated;

        NativeSprite(@NotNull TextureAtlasSprite sprite, int[] indexes, int[] ends, int[] durations, int cycle,
            boolean interpolated) {
            this.sprite = sprite;
            this.indexes = indexes;
            this.ends = ends;
            this.cycle = cycle;
            this.durations = durations;
            this.interpolated = interpolated;
        }

        int position(int tick) {
            int local = tick % cycle;
            int index = 0;
            while (local >= ends[index]) index++;
            return index;
        }

        int[][] pixels(int tick) {
            return sprite.getFrameTextureData(indexes[position(tick)]);
        }

        void write(int tick, int level, @NotNull IntBuffer output) {
            int position = position(tick);
            int[] current = sprite.getFrameTextureData(indexes[position])[level];
            int elapsed = tick % cycle - (position == 0 ? 0 : ends[position - 1]);
            if (!interpolated || elapsed == 0 || indexes[position] == indexes[(position + 1) % indexes.length]) {
                output.put(current);
                return;
            }
            int[] next = sprite.getFrameTextureData(indexes[(position + 1) % indexes.length])[level];
            double weight = 1.0 - (double) elapsed / durations[position];
            for (int index = 0; index < current.length; index++) {
                int first = current[index];
                int second = next[index];
                int red = (int) (weight * (first >> 16 & 255) + (1.0 - weight) * (second >> 16 & 255));
                int green = (int) (weight * (first >> 8 & 255) + (1.0 - weight) * (second >> 8 & 255));
                int blue = (int) (weight * (first & 255) + (1.0 - weight) * (second & 255));
                output.put(first & 0xff000000 | red << 16 | green << 8 | blue);
            }
        }
    }
}
