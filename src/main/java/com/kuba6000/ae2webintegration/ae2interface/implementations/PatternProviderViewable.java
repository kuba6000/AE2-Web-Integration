package com.kuba6000.ae2webintegration.ae2interface.implementations;

import com.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import com.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

import appeng.api.util.DimensionalCoord;
import appeng.api.util.IInterfaceViewable;

public class PatternProviderViewable extends IAEStrongObject<IInterfaceViewable> implements IPatternProviderViewable {

    public PatternProviderViewable(IInterfaceViewable object) {
        super(object);
    }

    @Override
    public String getName() {
        return object.getName();
    }

    @Override
    public DimensionalCoords getLocation() {
        DimensionalCoord coord = get().getLocation();
        return new DimensionalCoords(coord.getDimension(), coord.x, coord.y, coord.z);
    }
}
