package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.CraftingHistory;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.ResourceHistory;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;

/** Current editable network settings with the active administrator bounds. */
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
