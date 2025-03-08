package com.kuba6000.ae2webintegration.core.interfaces;

import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public interface IAEGrid {

    boolean isValid();

    IAECraftingGrid getCraftingGrid();

    IAEPathingGrid getPathingGrid();

    IAEStorageGrid getStorageGrid();

    boolean isEmpty();

    boolean internalObjectEquals(IAEGrid obj);

    IAEGrid createUnpooledCopy();

    void reUse(IAEGrid object);
}
