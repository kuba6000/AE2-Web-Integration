package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import com.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

import appeng.api.util.DimensionalCoord;
import appeng.api.util.IInterfaceViewable;

@Mixin(value = IInterfaceViewable.class)
public interface PatternProviderViewableMixin extends IPatternProviderViewable {

    @Override
    public default String web$getName() {
        return ((IInterfaceViewable) (Object) this).getName();
    }

    @Override
    public default DimensionalCoords web$getLocation() {
        DimensionalCoord coord = ((IInterfaceViewable) (Object) this).getLocation();
        return new DimensionalCoords(coord.getDimension(), coord.x, coord.y, coord.z);
    }
}
