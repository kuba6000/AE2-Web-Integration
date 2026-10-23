package pl.kuba6000.ae2webintegration.core.config;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** Read-only active settings view and an opaque runtime revision for conditional saves. */
@Desugar
public record ServerSettingsSnapshot(@NotNull String revision, @NotNull EditableServerSettings settings,
    @NotNull ReadOnly readOnly) {

    /** File-only settings and secret-presence flags; never secret values or lengths. */
    @Desugar
    public record ReadOnly(int port, boolean allowServerSettingsEditing, boolean publicMode,
        boolean allowNoPasswordOnLocalhost, @NotNull String trustedProxies, int maxRequestsBeforeLoggedInPerMinute,
        boolean adminPasswordConfigured, boolean discordWebhookConfigured, boolean ntfyPasswordConfigured) {}
}
