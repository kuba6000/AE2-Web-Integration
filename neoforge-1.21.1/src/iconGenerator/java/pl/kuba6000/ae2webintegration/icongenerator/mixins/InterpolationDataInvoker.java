package pl.kuba6000.ae2webintegration.icongenerator.mixins;

import net.minecraft.client.renderer.texture.SpriteContents;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Calls the final renderer interpolation implementation even when an overwrite narrows its access. */
@Mixin(SpriteContents.InterpolationData.class)
public interface InterpolationDataInvoker {

    @Invoker("uploadInterpolatedFrame")
    void ae2webicons$uploadInterpolatedFrame(int x, int y, @NotNull SpriteContents.Ticker ticker);
}
