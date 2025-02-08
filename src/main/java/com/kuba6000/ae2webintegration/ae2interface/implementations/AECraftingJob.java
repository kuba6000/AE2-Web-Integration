package com.kuba6000.ae2webintegration.ae2interface.implementations;

import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;

import appeng.api.networking.crafting.ICraftingJob;

public class AECraftingJob extends IAEStrongObject<ICraftingJob> implements IAECraftingJob {

    public AECraftingJob(ICraftingJob object) {
        super(object);
    }

    @Override
    public boolean isSimulation() {
        return get().isSimulation();
    }

    @Override
    public long getByteTotal() {
        return get().getByteTotal();
    }

    @Override
    public void populatePlan(IItemList plan) {
        get().populatePlan(((ItemList) plan).get());
    }
}
