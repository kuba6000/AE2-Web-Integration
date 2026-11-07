package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.block.Block;
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
    private void web$controllerRemoved(World world, int x, int y, int z, Block oldBlock, int metadata,
        CallbackInfo callback) {
        // 1.7.10 also calls breakBlock for metadata changes; the replacement is already in world storage.
        if (!world.isRemote && (Object) this instanceof BlockController && world.getBlock(x, y, z) != (Object) this) {
            CoreEngine.GRID_IDENTITIES.controllerRemoved(new DimensionalCoords(world.provider.dimensionId, x, y, z));
        }
    }
}
