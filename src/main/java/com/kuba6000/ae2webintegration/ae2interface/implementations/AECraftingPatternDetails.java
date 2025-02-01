package com.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.Arrays;

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
        return Arrays.stream(get().getCondensedOutputs())
            .map(ItemStack::new)
            .toArray(IItemStack[]::new);
    }
}
