package com.kuba6000.ae2webintegration.core.interfaces;

import java.util.List;

public interface ICraftingPlanSummary {

    long web$getUsedBytes();

    boolean web$isSimulation();

    List<ICraftingPlanSummaryEntry> web$getEntries();

}
