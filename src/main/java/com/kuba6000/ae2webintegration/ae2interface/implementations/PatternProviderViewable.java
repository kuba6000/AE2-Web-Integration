package com.kuba6000.ae2webintegration.ae2interface.implementations;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import com.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import com.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

import appeng.helpers.IInterfaceHost;

public class PatternProviderViewable extends IAEStrongObject<IInterfaceHost> implements IPatternProviderViewable {

    public PatternProviderViewable(IInterfaceHost object) {
        super(object);
    }

    @Override
    public String getName() {
        return object.getInterfaceDuality()
            .getTermName();
    }

    @Override
    public DimensionalCoords getLocation() {
        TileEntity te = get().getTileEntity();
        BlockPos pos = te.getPos();
        return new DimensionalCoords(te.getWorld(), pos.getX(), pos.getY(), pos.getZ());
    }
}
