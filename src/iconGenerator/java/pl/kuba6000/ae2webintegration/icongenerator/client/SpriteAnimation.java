package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import pl.kuba6000.ae2webintegration.core.icons.export.IIconAnimation;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCapture.RenderFailure;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.CaptureKind;
import pl.kuba6000.ae2webintegration.core.icons.export.SpriteTimeline;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.icongenerator.mixins.InterpolationDataInvoker;

/** Version-local GUI classification and controlled uploads; scratch starts only when its cursor runs. */
final class SpriteAnimation implements IIconAnimation {

    enum Selection {

        STATIC("staticAnimationCandidates"),
        CUSTOM_RENDERER("sampledCustomAnimationPlans"),
        SPRITE_IMPLEMENTATION("unsupportedSpriteAnimations"),
        SCRATCH_LIMIT("unsupportedAnimationScratch");

        final String statistic;

        Selection(String statistic) {
            this.statistic = statistic;
        }
    }

    private static final long MAX_SCRATCH_BYTES = 16L * 1024 * 1024;
    private final Minecraft minecraft;
    private final ModernIconRenderer renderer;
    private final IconCandidate candidate;
    private final StableKey key;
    private final List<TextureAtlasSprite> sprites;
    private final @Nullable SpriteTimeline timeline;
    private final List<SpriteContents.Ticker> tickers = new ArrayList<>();
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
                ResourceLocation still = IClientFluidTypeExtensions.of(fluid.getFluid())
                    .getStillTexture(fluid);
                if (still == null) return null;
                used.add(
                    minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                        .apply(still));
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
            long scratchBytes = 0;
            for (TextureAtlasSprite sprite : sprites) {
                if (sprite.getClass() != TextureAtlasSprite.class || sprite.contents()
                    .getClass() != SpriteContents.class) {
                    renderer.animationSelection(Selection.SPRITE_IMPLEMENTATION);
                    return null;
                }
                for (int mip = 0; mip < sprite.contents().byMipLevel.length; mip++) {
                    // GPU rectangle plus native interpolation image; conservative for non-interpolated tracks.
                    scratchBytes += (long) (sprite.contents()
                        .width() >> mip) * (sprite.contents()
                            .height() >> mip)
                        * 8;
                }
            }
            if (scratchBytes > MAX_SCRATCH_BYTES) {
                renderer.animationSelection(Selection.SCRATCH_LIMIT);
                return null;
            }
            List<SpriteTimeline.Track> tracks = new ArrayList<>();
            for (TextureAtlasSprite sprite : sprites) {
                SpriteContents.AnimatedTexture animation = sprite.contents().animatedTexture;
                tracks.add(
                    new SpriteTimeline.Track(
                        animation.frames.stream()
                            .mapToInt(frame -> frame.time)
                            .toArray(),
                        animation.interpolateFrames));
            }
            SpriteTimeline timeline = SpriteTimeline.create(tracks);
            renderer.animationPlan(timeline.truncated());
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
        BakedModel model = minecraft.getItemRenderer()
            .getModel(item, minecraft.level, minecraft.player, 0);
        if (item.is(Items.TRIDENT) || item.is(Items.SPYGLASS)) {
            model = minecraft.getModelManager()
                .getModel(ModelResourceLocation.vanilla(item.is(Items.TRIDENT) ? "trident" : "spyglass", "inventory"));
        }
        model = ForgeHooksClient.handleCameraTransforms(new PoseStack(), model, ItemDisplayContext.GUI, false);
        if (model.isCustomRenderer()) return true;
        RandomSource random = RandomSource.create();
        for (BakedModel pass : model.getRenderPasses(item, true)) {
            // Native ItemRenderer obtains the same quad lists separately for each render type.
            for (RenderType ignored : pass.getRenderTypes(item, true)) {
                for (Direction direction : Direction.values()) {
                    random.setSeed(42);
                    pass.getQuads(null, direction, random)
                        .forEach(quad -> sprites.add(quad.getSprite()));
                }
                random.setSeed(42);
                pass.getQuads(null, null, random)
                    .forEach(quad -> sprites.add(quad.getSprite()));
            }
        }
        return false;
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
        if (tickers.isEmpty()) {
            for (TextureAtlasSprite sprite : sprites) tickers.add(
                (SpriteContents.Ticker) sprite.contents()
                    .createTicker());
        }
        SpriteTimeline.Frame frame = timeline.frames()
            .get(step++);
        RenderFailure drawFailure = null;
        try (RenderState ignored = renderer.controlledState();
            AtlasPixels pixels = new AtlasPixels(minecraft, sprites)) {
            pixels.changing();
            try {
                for (int i = 0; i < sprites.size(); i++) upload(sprites.get(i), tickers.get(i), frame.tick());
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

    private void upload(TextureAtlasSprite sprite, SpriteContents.Ticker ticker, int tick) throws RenderFailure {
        RenderSystem.bindTexture(
            minecraft.getTextureManager()
                .getTexture(sprite.atlasLocation())
                .getId());
        RenderFailure uploadFailure = null;
        try {
            SpriteContents.AnimatedTexture animation = sprite.contents().animatedTexture;
            long cycle = 0;
            for (SpriteContents.FrameInfo frame : animation.frames) cycle += frame.time;
            long remaining = tick % cycle;
            int index = 0;
            while (remaining >= animation.frames.get(index).time) remaining -= animation.frames.get(index++).time;
            ticker.frame = index;
            ticker.subFrame = (int) remaining;
            animation.uploadFrame(sprite.getX(), sprite.getY(), animation.frames.get(index).index);
            if (ticker.interpolationData != null && remaining != 0)
                ((InterpolationDataInvoker) (Object) ticker.interpolationData)
                    .ae2webicons$uploadInterpolatedFrame(sprite.getX(), sprite.getY(), ticker);
        } catch (RuntimeException | LinkageError failure) {
            uploadFailure = ModernIconRenderer.animationFailure(failure);
        }
        // A callback exception cannot hide a GPU transfer error. Atlas/state closure still follows.
        AtlasPixels.checkTransfer();
        if (uploadFailure != null) throw uploadFailure;
    }

    @Override
    public void close() {
        Throwable failure = null;
        for (SpriteContents.Ticker ticker : tickers) {
            try {
                ticker.close();
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) {
                    if (problem instanceof VirtualMachineError || problem instanceof ThreadDeath) {
                        problem.addSuppressed(failure);
                        failure = problem;
                    } else failure.addSuppressed(problem);
                }
            }
        }
        tickers.clear();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }
}
