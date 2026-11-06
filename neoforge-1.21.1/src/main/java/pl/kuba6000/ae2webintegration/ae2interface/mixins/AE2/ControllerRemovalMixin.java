package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.block.AEBaseEntityBlock;
import appeng.block.networking.ControllerBlock;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;

@Mixin(AEBaseEntityBlock.class)
public class ControllerRemovalMixin {

    @Inject(method = "onRemove", at = @At("HEAD"))
    private void web$controllerRemoved(BlockState state, Level level, BlockPos position, BlockState newState,
        boolean isMoving, CallbackInfo ci) {
        // Block entity/node removal also occurs on chunk unload; only a block replacement is destruction.
        if (!level.isClientSide() && state.getBlock() instanceof ControllerBlock && !newState.is(state.getBlock())) {
            CoreEngine.GRID_IDENTITIES.controllerRemoved(
                new DimensionalCoords(
                    level.dimension()
                        .location()
                        .toString(),
                    position.getX(),
                    position.getY(),
                    position.getZ()));
        }
    }
}
