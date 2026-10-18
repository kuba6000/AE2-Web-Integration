package pl.kuba6000.ae2webintegration.core.config;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** Immutable administrator policy captured when a world opens. */
@Desugar
public record DataPolicy(@NotNull Range stockSampleIntervalSeconds, @NotNull Range flowFlushIntervalSeconds,
    @NotNull Range historyRetentionDays, int historyMaxRowsPerGrid) {

    public static @NotNull DataPolicy from(@NotNull ConfigSettings.Data settings) {
        return new DataPolicy(
            Range.from(settings.stockSampleIntervalSeconds),
            Range.from(settings.flowFlushIntervalSeconds),
            Range.from(settings.historyRetentionDays),
            settings.historyMaxRowsPerGrid);
    }

    /** Positive integer bounds and the initial value for a new network. */
    @Desugar
    public record Range(int min, int max, int defaultValue) {

        private static @NotNull Range from(@NotNull ConfigSettings.Range range) {
            return new Range(range.min, range.max, range.defaultValue);
        }

        public boolean contains(int value) {
            return value >= min && value <= max;
        }

        public int clamp(int value) {
            return Math.max(min, Math.min(max, value));
        }

        public void validate(int value) {
            if (!contains(value)) throw new IllegalArgumentException("Setting is outside the active server bounds");
        }
    }
}
