package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Nameable;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import com.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.networking.IGridNode;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.InWorldGridNode;

@Mixin(value = IGridNode.class)
public interface PatternProviderViewableMixin extends IPatternProviderViewable {

    @Override
    public default String web$getName() {
        Object o = ((IGridNode) (Object) this).getOwner();
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
        if (this instanceof InWorldGridNode) {
            BlockPos pos = ((InWorldGridNode) this).getLocation();
            return new DimensionalCoords(((InWorldGridNode) this).getLevel(), pos.getX(), pos.getY(), pos.getZ());
        }
        return null;

    }
}
