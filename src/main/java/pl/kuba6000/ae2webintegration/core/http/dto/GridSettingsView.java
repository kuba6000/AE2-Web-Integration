package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.Data;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;

/** Current editable network settings with the active administrator bounds. */
@Desugar
public record GridSettingsView(boolean isTracked, boolean isStorageTracked, boolean isFlowTracked, @NotNull String name,
    int stockSampleIntervalSeconds, int flowFlushIntervalSeconds, int historyRetentionDays, @NotNull Data limits) {

    public static @NotNull GridSettingsView from(@NotNull GridSettingsData settings, @NotNull Data limits) {
        return new GridSettingsView(
            settings.isTracked(),
            settings.isStorageTracked(),
            settings.isFlowTracked(),
            settings.getName(),
            settings.getStockSampleIntervalSeconds(),
            settings.getFlowFlushIntervalSeconds(),
            settings.getHistoryRetentionDays(),
            limits);
    }
}
