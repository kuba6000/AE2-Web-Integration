package com.kuba6000.ae2webintegration.ae2interface.implementations;

import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

import appeng.api.networking.crafting.ICraftingPatternDetails;

public class AECraftingPatternDetails extends IAEStrongObject<ICraftingPatternDetails>
    implements IAECraftingPatternDetails {

    public AECraftingPatternDetails(ICraftingPatternDetails object) {
        super(object);
    }

    @Override
    public IItemStack[] getCondensedOutputs() {
        return (IItemStack[]) get().getCondensedOutputs();
    }
}
