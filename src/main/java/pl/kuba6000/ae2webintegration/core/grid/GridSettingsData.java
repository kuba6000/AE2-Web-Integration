package pl.kuba6000.ae2webintegration.core.grid;

import com.github.bsideup.jabel.Desugar;

/** Persisted grid configuration, independent of native identity and runtime crafting state. */
@Desugar
public record GridSettingsData(boolean isTracked) {

    public GridSettingsData() {
        this(false);
    }

    public boolean isDefault() {
        return !isTracked;
    }
}
