package pl.kuba6000.ae2webintegration.core.config;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** Detached editable file contents and an opaque revision for conditional saves. */
@Desugar
public record ServerSettingsSnapshot(@NotNull String revision, @NotNull EditableServerSettings settings) {}
