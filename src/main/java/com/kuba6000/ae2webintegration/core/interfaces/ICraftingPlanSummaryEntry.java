package com.kuba6000.ae2webintegration.core.interfaces;

public interface ICraftingPlanSummaryEntry {

    IAEKey web$getWhat();

    long web$getMissingAmount();

    long web$getStoredAmount();

    long web$getCraftAmount();

}
