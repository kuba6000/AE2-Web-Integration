package pl.kuba6000.ae2webintegration.core.interfaces;

import java.util.List;

public interface ICraftingPlanSummary {

    boolean web$isSimulation();

    List<ICraftingPlanSummaryEntry> web$getEntries();

}
