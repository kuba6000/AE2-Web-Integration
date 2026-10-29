package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.ReflectionHelper;
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
    private static final Method GUTTER = gutterAccessor();
    private static final int MAX_TICKS = 1200;
    private static final int MAX_FRAMES = 256;
    private static final Field METADATA = ReflectionHelper
        .findField(TextureAtlasSprite.class, "animationMetadata", "field_110982_k");
    private final LegacyIconRenderer renderer;
    private final IconCandidate candidate;
    private final StableKey key;
    private final @Nullable ResourceLocation atlas;
    private final List<NativeSprite> sprites;
    private final List<SpriteTimeline.Frame> frames;
    private int frame;

    private NativeIconAnimation(@NotNull LegacyIconRenderer renderer, @NotNull IconCandidate candidate,
        @NotNull StableKey key, @Nullable ResourceLocation atlas, @NotNull List<NativeSprite> sprites,
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
        ResourceLocation atlas = stack.getItemSpriteNumber() == 0 ? TextureMap.locationBlocksTexture
            : TextureMap.locationItemsTexture;
        IItemRenderer custom = MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.INVENTORY);
        if (custom != null) {
            renderer.animationCustomRenderers.merge(
                custom.getClass()
                    .getName(),
                1L,
                Long::sum);
            return sampled(renderer, candidate, key);
        }
        List<IIcon> icons = new ArrayList<>();
        if (stack.getItemSpriteNumber() == 0) {
            Block block = Block.getBlockFromItem(stack.getItem());
            int renderType = block.getRenderType();
            if (RenderBlocks.renderItemIn3d(renderType)) {
                if (renderType != 0) {
                    renderer.animationUnsupportedShape++;
                    return null;
                }
                // RenderBlocks normal-cube inventory path fixes the front face of these vanilla blocks.
                int metadata = block == Blocks.dispenser || block == Blocks.dropper || block == Blocks.furnace ? 3
                    : stack.getItemDamage();
                for (int side = 0; side < 6; side++) icons.add(block.getIcon(side, metadata));
                return plan(renderer, candidate, key, atlas, icons);
            }
        }
        if (stack.getItem()
            .requiresMultipleRenderPasses()) atlas = TextureMap.locationItemsTexture;
        int passes = stack.getItem()
            .requiresMultipleRenderPasses()
                ? stack.getItem()
                    .getRenderPasses(stack.getItemDamage())
                : 1;
        for (int pass = 0; pass < passes; pass++) {
            icons.add(
                stack.getItem()
                    .requiresMultipleRenderPasses()
                        ? stack.getItem()
                            .getIcon(stack, pass)
                        : stack.getIconIndex());
        }
        return plan(renderer, candidate, key, atlas, icons);
    }

    static @Nullable IIconAnimation fluid(@NotNull LegacyIconRenderer renderer, @NotNull IconCandidate candidate,
        @NotNull StableKey key, @NotNull FluidStack stack) {
        return plan(
            renderer,
            candidate,
            key,
            TextureMap.locationBlocksTexture,
            Collections.singletonList(
                stack.getFluid()
                    .getIcon(stack)));
    }

    static @NotNull IIconAnimation sampled(@NotNull LegacyIconRenderer renderer, @NotNull IconCandidate candidate,
        @NotNull StableKey key) {
        return new NativeIconAnimation(
            renderer,
            candidate,
            key,
            null,
            Collections.emptyList(),
            Collections.emptyList());
    }

    private static @Nullable IIconAnimation plan(@NotNull LegacyIconRenderer renderer, @NotNull IconCandidate candidate,
        @NotNull StableKey key, @NotNull ResourceLocation atlas, @NotNull List<IIcon> icons) {
        List<NativeSprite> timelines = new ArrayList<>();
        List<SpriteTimeline.Track> tracks = new ArrayList<>();
        long scratchBytes = 0;
        for (IIcon icon : icons) {
            if (icon == null || icon.getClass() != TextureAtlasSprite.class) {
                renderer.animationUnsupportedSprite++;
                return null;
            }
            TextureAtlasSprite sprite = (TextureAtlasSprite) icon;
            if (!sprite.hasAnimationMetadata()) continue;
            if (timelines.stream()
                .anyMatch(t -> t.sprite == sprite)) continue;
            int gutter = gutter(sprite);
            if (gutter < 0 || gutter > 4096 || sprite.getOriginX() < gutter || sprite.getOriginY() < gutter) {
                renderer.animationUnsupportedAtlas++;
                return null;
            }
            NativeSprite timeline = timeline(sprite, gutter);
            if (timeline == null) {
                renderer.animationUnsupportedTimeline++;
                return null;
            }
            scratchBytes += timeline.scratchBytes();
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

    private static @Nullable Method gutterAccessor() {
        if (!Loader.isModLoaded("angelica")) return null;
        try {
            return TextureAtlasSprite.class.getMethod("angelica$getGutterWidth");
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static int gutter(@NotNull TextureAtlasSprite sprite) {
        if (!Loader.isModLoaded("angelica")) return 0;
        if (GUTTER == null) return -1;
        try {
            return (Integer) GUTTER.invoke(sprite);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Cannot read Angelica sprite gutter", cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read Angelica sprite gutter", failure);
        }
    }

    private static @Nullable NativeSprite timeline(@NotNull TextureAtlasSprite sprite, int gutter) {
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
            return new NativeSprite(sprite, indexes, ends, durations, (int) total, false, gutter);
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
        assert atlas != null : "Controlled animation has an atlas";
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
            try {
                scope.select(
                    frames.get(frame)
                        .tick());
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
        final int gutter;

        NativeSprite(@NotNull TextureAtlasSprite sprite, int[] indexes, int[] ends, int[] durations, int cycle,
            boolean interpolated, int gutter) {
            this.sprite = sprite;
            this.indexes = indexes;
            this.ends = ends;
            this.cycle = cycle;
            this.durations = durations;
            this.interpolated = interpolated;
            this.gutter = gutter;
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

        int width(int level) {
            return (sprite.getIconWidth() >> level) + 2 * (gutter >> level);
        }

        int height(int level) {
            return (sprite.getIconHeight() >> level) + 2 * (gutter >> level);
        }

        long scratchBytes() {
            long bytes = 0;
            int[][] data = pixels(0);
            for (int level = 0; level < data.length; level++) {
                if (data[level] != null) bytes += (long) width(level) * height(level) * Integer.BYTES;
            }
            return bytes;
        }

        void write(int tick, int level, @NotNull IntBuffer output) {
            int[] pixels = pixels(tick)[level];
            int width = sprite.getIconWidth() >> level;
            int height = sprite.getIconHeight() >> level;
            if (pixels == null || pixels.length != width * height)
                throw new IllegalStateException("Sprite mip dimensions changed during export");
            int padding = gutter >> level;
            if (padding == 0) {
                output.put(pixels);
                return;
            }
            // Angelica 2.2.25 SpritePadding duplicates edge texels into each mip's gutter.
            for (int y = -padding; y < height + padding; y++) {
                int row = Math.max(0, Math.min(height - 1, y)) * width;
                for (int x = 0; x < padding; x++) output.put(pixels[row]);
                output.put(pixels, row, width);
                for (int x = 0; x < padding; x++) output.put(pixels[row + width - 1]);
            }
        }
    }
}
