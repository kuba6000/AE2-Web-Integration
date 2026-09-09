package pl.kuba6000.ae2webintegration.core.interfaces;

public interface ICraftingPlanSummaryEntry {

    IAEKey web$getWhat();

    long web$getMissingAmount();

    long web$getStoredAmount();

    long web$getCraftAmount();

    /** Crafting steps; GTNH-only metric — default 0 on modern AE2. */
    default long web$getCraftSteps() {
        return 0L;
    }

}
