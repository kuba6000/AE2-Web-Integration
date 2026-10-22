package pl.kuba6000.ae2webintegration.core.config;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** Detached file settings and an opaque revision for conditional saves. */
@Desugar
public record ServerSettingsSnapshot(@NotNull String revision, @NotNull EditableServerSettings settings,
    @NotNull ReadOnly readOnly) {

    /** File-only settings. Password state deliberately reveals neither its value nor its length. */
    @Desugar
    public record ReadOnly(int port, boolean publicMode, boolean allowNoPasswordOnLocalhost,
        boolean adminPasswordConfigured) {}
}
