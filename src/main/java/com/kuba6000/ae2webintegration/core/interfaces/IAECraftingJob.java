package com.kuba6000.ae2webintegration.core.interfaces;

public interface IAECraftingJob {

    boolean isSimulation();

    long getByteTotal();

    void populatePlan(IItemList plan);

}
