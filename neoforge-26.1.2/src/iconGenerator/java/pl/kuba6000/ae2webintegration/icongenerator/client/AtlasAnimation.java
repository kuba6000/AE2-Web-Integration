package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;

/** Replays native sprite cursors for capture, then redraws their original frames at every padded mip. */
final class AtlasAnimation implements AutoCloseable {

    private final List<Cursor> cursors = new ArrayList<>();
    private boolean changing;

    AtlasAnimation(Minecraft minecraft, List<TextureAtlasSprite> sprites) {
        for (TextureAtlasSprite sprite : sprites) {
            TextureAtlas atlas = atlas(minecraft, sprite);
            SpriteContents.AnimationState animation = atlas.animatedTexturesStates.stream()
                .filter(state -> state.animationInfo == sprite.contents().animatedTexture)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Animated sprite has no native atlas cursor"));
            cursors.add(new Cursor(atlas, animation, animation.frame, animation.subFrame, animation.isDirty));
        }
    }

    static TextureAtlas atlas(Minecraft minecraft, TextureAtlasSprite sprite) {
        // Sprite locations are texture ids; AtlasManager.getAtlasOrThrow takes the atlas definition id instead.
        return (TextureAtlas) minecraft.getTextureManager()
            .getTexture(sprite.atlasLocation());
    }

    void animate(int tick) {
        changing = true;
        for (Cursor cursor : cursors) {
            SpriteContents.AnimationState state = cursor.animation;
            var frames = state.animationInfo.frames;
            long cycle = 0;
            for (SpriteContents.FrameInfo frame : frames) cycle += frame.time();
            long remaining = tick % cycle;
            int index = 0;
            while (remaining >= frames.get(index)
                .time())
                remaining -= frames.get(index++)
                    .time();
            state.frame = index;
            state.subFrame = (int) remaining;
            cursor.draw();
        }
    }

    @Override
    public void close() {
        Throwable failure = null;
        for (Cursor cursor : cursors) {
            try {
                cursor.animation.frame = cursor.frame;
                cursor.animation.subFrame = cursor.subFrame;
                if (changing) cursor.draw();
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (problem instanceof VirtualMachineError || problem instanceof ThreadDeath) {
                    problem.addSuppressed(failure);
                    failure = problem;
                } else failure.addSuppressed(problem);
            } finally {
                cursor.animation.isDirty = cursor.dirty;
            }
        }
        cursors.clear();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }

    private record Cursor(TextureAtlas atlas, SpriteContents.AnimationState animation, int frame, int subFrame,
        boolean dirty) {

        void draw() {
            // Replaying the saved native frame avoids target GlCommandEncoder.copyTextureToTexture defects:
            // its 26.1.2 backend ignores mipLevel and treats rectangle widths as framebuffer endpoints.
            animation.isDirty = true;
            for (int mip = 0; mip < atlas.mipViews.length; mip++) {
                try (RenderPass pass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                        () -> "ae2webicons controlled animation",
                        atlas.mipViews[mip],
                        OptionalInt.empty())) {
                    animation.drawToAtlas(pass, animation.getDrawUbo(mip));
                }
            }
        }
    }
}
