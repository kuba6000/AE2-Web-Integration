package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.networking.IGridNode;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.me.InWorldGridNode;
import appeng.parts.AEBasePart;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

@Mixin(value = IGridNode.class, remap = false)
public interface PatternProviderViewableMixin extends IPatternProviderViewable {

    @Shadow
    Object getOwner();

    @Override
    default String web$getName() {
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
    default DimensionalCoords web$getLocation() {
        IGridNode node = (IGridNode) this;
        Object owner = getOwner();
        BlockPos pos;
        if (node instanceof InWorldGridNode inWorld) pos = inWorld.getLocation();
        else if (owner instanceof PatternProviderLogicHost host) pos = host.getBlockEntity()
            .getBlockPos();
        else if (owner instanceof AEBasePart part) pos = part.getBlockEntity()
            .getBlockPos();
        else if (owner instanceof BlockEntity block) pos = block.getBlockPos();
        else return null;
        return new DimensionalCoords(
            node.getLevel()
                .dimension()
                .location()
                .toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ());
    }
}
