package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.networking.IGrid;
import appeng.me.GridNode;
import appeng.util.Platform;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPermissions;

@Mixin(value = GridNode.class, remap = false)
public abstract class GridNodeTopologyMixin {

    @WrapMethod(method = "setPlayerID(I)V")
    private void web$setPlayerID(int playerID, Operation<Void> original) {
        GridNode node = (GridNode) (Object) this;
        int previousOwner = node.getPlayerID();
        original.call(playerID);
        if (Platform.isServer() && previousOwner != node.getPlayerID()) {
            // Both supported legacy getGrid implementations only read myGrid; they never create one.
            IGrid grid = node.getGrid();
            if (grid != null) ((IGridPermissions) grid).web$ownerChanged(node);
        }
    }
}
