package pl.kuba6000.ae2webintegration.core.config;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/**
 * Current server settings.
 *
 * @param revision opaque version to send when saving settings
 * @param settings editable settings
 * @param readOnly settings that cannot be changed through the API
 */
@Desugar
public record ServerSettingsSnapshot(@NotNull String revision, @NotNull EditableServerSettings settings,
    @NotNull ReadOnly readOnly) {

    /** File-only settings and flags indicating whether credentials are configured. Secret values are not returned. */
    @Desugar
    public record ReadOnly(int port, boolean allowServerSettingsEditing, boolean publicMode,
        boolean allowNoPasswordOnLocalhost, @NotNull String trustedProxies, int maxRequestsBeforeLoggedInPerMinute,
        boolean adminPasswordConfigured, boolean discordWebhookConfigured, boolean ntfyPasswordConfigured) {}
}
