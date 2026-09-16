package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.block.AEBaseTileBlock;
import appeng.block.networking.BlockController;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;

@Mixin(AEBaseTileBlock.class)
public abstract class ControllerRemovalMixin {

    @Inject(method = "breakBlock", at = @At("HEAD"))
    private void web$controllerRemoved(World world, BlockPos position, IBlockState oldState, CallbackInfo callback) {
        if (!world.isRemote && (Object) this instanceof BlockController
            && world.getBlockState(position)
                .getBlock() != (Object) this) {
            CoreEngine.GRID_IDENTITIES.controllerRemoved(
                new DimensionalCoords(
                    world.provider.getDimension(),
                    position.getX(),
                    position.getY(),
                    position.getZ()));
        }
    }
}
