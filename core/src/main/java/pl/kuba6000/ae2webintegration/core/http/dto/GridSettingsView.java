package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.CraftingHistory;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.ResourceHistory;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;

/**
 * Current network settings and administrator limits.
 *
 * @param isTracked                    whether crafting jobs are recorded
 * @param trackMachineCrafting         whether new machine-requested jobs are recorded when crafting tracking is enabled
 * @param isStorageTracked             whether stored resource quantities are sampled
 * @param isFlowTracked                whether resource input and output are recorded
 * @param name                         custom network name; empty when unnamed
 * @param stockSampleIntervalSeconds   seconds between saved stock samples, independent of live terminal updates
 * @param flowFlushIntervalSeconds     seconds covered by each saved input/output total
 * @param historyRetentionDays         resource history retention in days, subject to the record limit
 * @param craftingHistoryRetentionDays completed or cancelled crafting history retention in days, subject to the job
 *                                     limit
 * @param limits                       allowed resource history intervals, retention and record limit
 * @param craftingLimits               allowed crafting history retention and job limit
 */
@Desugar
public record GridSettingsView(boolean isTracked, boolean trackMachineCrafting, boolean isStorageTracked,
    boolean isFlowTracked, @NotNull String name, int stockSampleIntervalSeconds, int flowFlushIntervalSeconds,
    int historyRetentionDays, int craftingHistoryRetentionDays, @NotNull ResourceHistory limits,
    @NotNull CraftingHistory craftingLimits) {

    public static @NotNull GridSettingsView from(@NotNull GridSettingsData settings, @NotNull ResourceHistory limits,
        @NotNull CraftingHistory craftingLimits) {
        return new GridSettingsView(
            settings.isTracked(),
            settings.isTrackMachineCrafting(),
            settings.isStorageTracked(),
            settings.isFlowTracked(),
            settings.getName(),
            settings.getStockSampleIntervalSeconds(),
            settings.getFlowFlushIntervalSeconds(),
            settings.getHistoryRetentionDays(),
            settings.getCraftingHistoryRetentionDays(),
            limits,
            craftingLimits);
    }
}
