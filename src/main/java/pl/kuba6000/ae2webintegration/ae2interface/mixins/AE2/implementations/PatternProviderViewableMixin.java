package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.networking.IGridNode;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.InWorldGridNode;
import appeng.parts.AEBasePart;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

@Mixin(value = IGridNode.class, remap = false)
public interface PatternProviderViewableMixin extends IPatternProviderViewable {

    @Shadow
    Object getOwner();

    @Override
    public default String web$getName() {
        Object o = getOwner();
        if (o instanceof PatternContainer) {
            PatternContainerGroup group = ((PatternContainer) o).getTerminalGroup();
            if (group != null) {
                return group.name()
                    .getString();
            }
        }
        if (o instanceof Nameable) return ((Nameable) o).getName()
            .getString();
        return null;
    }

    @Override
    public default DimensionalCoords web$getLocation() {
        BlockPos pos;
        Level level;
        if (this instanceof InWorldGridNode) {
            pos = ((InWorldGridNode) this).getLocation();
            level = ((InWorldGridNode) this).getLevel();
        } else {
            Object o = getOwner();
            if (o instanceof AEBasePart) {
                pos = ((AEBasePart) o).getBlockEntity()
                    .getBlockPos();
                level = ((AEBasePart) o).getBlockEntity()
                    .getLevel();
            } else if (o instanceof BlockEntity) {
                pos = ((BlockEntity) o).getBlockPos();
                level = ((BlockEntity) o).getLevel();
            } else {
                return null; // Not a valid location
            }
        }
        return new DimensionalCoords(level, pos.getX(), pos.getY(), pos.getZ());
    }
}
