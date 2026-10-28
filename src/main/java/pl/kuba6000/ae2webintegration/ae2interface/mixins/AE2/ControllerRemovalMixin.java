package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import appeng.block.networking.ControllerBlock;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;

@Mixin(LevelChunk.class)
public class ControllerRemovalMixin {

    @Shadow
    @Final
    private Level level;

    @ModifyExpressionValue(
        method = "setBlockState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private @NotNull BlockState web$controllerRemoved(@NotNull BlockState oldState,
        @Local(argsOnly = true) @NotNull BlockPos position, @Local(argsOnly = true) @NotNull BlockState newState) {
        // Observe actual replacement before node teardown, including quiet updates; unload never uses this path.
        if (!level.isClientSide() && oldState.getBlock() instanceof ControllerBlock
            && !newState.is(oldState.getBlock())) {
            CoreEngine.GRID_IDENTITIES.controllerRemoved(
                new DimensionalCoords(
                    level.dimension()
                        .identifier()
                        .toString(),
                    position.getX(),
                    position.getY(),
                    position.getZ()));
        }
        return oldState;
    }
}
