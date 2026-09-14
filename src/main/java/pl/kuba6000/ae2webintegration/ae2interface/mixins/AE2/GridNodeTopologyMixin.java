package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.me.GridNode;
import appeng.util.Platform;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.GridDiscovery;
import pl.kuba6000.ae2webintegration.core.GridAccessSessions;

@Mixin(value = GridNode.class, remap = false)
public abstract class GridNodeTopologyMixin {

    @WrapMethod(method = "setPlayerID(I)V")
    private void web$setPlayerID(int playerID, Operation<Void> original) {
        GridNode node = (GridNode) (Object) this;
        int previousOwner = node.getPlayerID();
        original.call(playerID);
        if (Platform.isServer() && previousOwner != node.getPlayerID()
            && GridDiscovery.accessSourceKind(
                node.getMachine()
                    .getClass())
                != null) {
            GridAccessSessions.permissionsChanged();
        }
    }
}
