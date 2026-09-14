package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.nbt.CompoundTag;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.me.GridNode;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;

@Mixin(value = GridNode.class, remap = false)
public class GridNodeUpdateMixin {

    @Inject(
        method = "setOwningPlayerId",
        at = @At(
            value = "FIELD",
            target = "Lappeng/me/GridNode;owningPlayerId:I",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void web$ownerChanged(int ownerPlayerId, CallbackInfo ci) {
        AE.sourcePermissionsChanged((GridNode) (Object) this);
    }

    @WrapMethod(method = "loadFromNBT")
    private void web$loadOwner(String name, CompoundTag data, Operation<Void> original) {
        GridNode node = (GridNode) (Object) this;
        int previousOwner = node.getOwningPlayerId();
        try {
            original.call(name, data);
        } finally {
            if (previousOwner != node.getOwningPlayerId()) AE.sourcePermissionsChanged(node);
        }
    }
}
