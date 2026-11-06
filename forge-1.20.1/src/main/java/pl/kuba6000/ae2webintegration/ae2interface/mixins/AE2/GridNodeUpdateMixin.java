package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.me.Grid;
import appeng.me.GridNode;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPermissions;

@Mixin(value = GridNode.class, remap = false)
public class GridNodeUpdateMixin {

    @Shadow
    private @Nullable Grid myGrid;

    @Inject(
        method = "setOwningPlayerId",
        at = @At(
            value = "FIELD",
            target = "Lappeng/me/GridNode;owningPlayerId:I",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void web$ownerChanged(int ownerPlayerId, CallbackInfo ci) {
        web$updatePermissions();
    }

    @WrapMethod(method = "loadFromNBT")
    private void web$loadOwner(String name, CompoundTag data, Operation<Void> original) {
        GridNode node = (GridNode) (Object) this;
        int previousOwner = node.getOwningPlayerId();
        try {
            original.call(name, data);
        } finally {
            if (previousOwner != node.getOwningPlayerId()) web$updatePermissions();
        }
    }

    @Unique
    private void web$updatePermissions() {
        if (myGrid != null) ((IGridPermissions) myGrid).web$updateNodePermissions((GridNode) (Object) this);
    }
}
