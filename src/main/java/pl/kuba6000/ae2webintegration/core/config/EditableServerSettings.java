package pl.kuba6000.ae2webintegration.core.config;

import org.jetbrains.annotations.NotNull;

import com.electronwill.nightconfig.core.conversion.Path;

/** Editable server configuration. Listener port and administrator password remain file-only. */
public final class EditableServerSettings {

    public @NotNull General general = new General();
    public @NotNull ConfigSettings.Notifications notifications = new ConfigSettings.Notifications();
    public @NotNull ConfigSettings.Discord discord = new ConfigSettings.Discord();
    public @NotNull ConfigSettings.Ntfy ntfy = new ConfigSettings.Ntfy();
    @Path("resource_history")
    public @NotNull ConfigSettings.ResourceHistory resourceHistory = new ConfigSettings.ResourceHistory();
    @Path("crafting_history")
    public @NotNull ConfigSettings.CraftingHistory craftingHistory = new ConfigSettings.CraftingHistory();
    @Path("resource_tracking")
    public @NotNull ConfigSettings.ResourceTracking resourceTracking = new ConfigSettings.ResourceTracking();

    public static final class General {

        @Path("allow_no_password_on_localhost")
        public boolean allowNoPasswordOnLocalhost;
        @Path("trusted_proxies")
        public @NotNull String trustedProxies = "";
        @Path("public_mode")
        public boolean publicMode;
        @Path("max_requests_before_logged_in_per_minute")
        public int maxRequestsBeforeLoggedInPerMinute;
        @Path("check_for_updates")
        public boolean checkForUpdates;
        @Path("default_theme")
        public @NotNull String defaultTheme = "";
    }
}
