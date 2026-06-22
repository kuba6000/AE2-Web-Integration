package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IAECraftingJob {

    boolean web$isSimulation();

    long web$getByteTotal();

    ICraftingPlanSummary web$generateSummary(IAEGrid grid);

}
