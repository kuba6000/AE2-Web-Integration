package pl.kuba6000.ae2webintegration.core.interfaces;

public interface ICraftingPlanSummaryEntry {

    IAEKey web$getWhat();

    long web$getMissingAmount();

    long web$getStoredAmount();

    long web$getCraftAmount();

    /** Per-resource crafting steps; default 0 when the platform does not support this metric. */
    default long web$getCraftSteps() {
        return 0L;
    }

}
