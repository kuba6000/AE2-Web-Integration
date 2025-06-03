package com.kuba6000.ae2webintegration.core.interfaces;

public interface IAECraftingJob {

    boolean web$isSimulation();

    long web$getByteTotal();

    void web$populatePlan(IItemList plan);

}
