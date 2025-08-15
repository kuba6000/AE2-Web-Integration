package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import com.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

import appeng.helpers.IInterfaceHost;

@Mixin(value = IInterfaceHost.class)
public interface PatternProviderViewableMixin extends IPatternProviderViewable {

    @Override
    public default String web$getName() {
        return ((IInterfaceHost) (Object) this).getInterfaceDuality()
            .getTermName();
    }

    @Override
    public default DimensionalCoords web$getLocation() {
        TileEntity te = ((IInterfaceHost) (Object) this).getTileEntity();
        BlockPos coord = te.getPos();
        return new DimensionalCoords(te.getWorld(), coord.getX(), coord.getY(), coord.getZ());
    }
}
