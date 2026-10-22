package pl.kuba6000.ae2webintegration.core.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.electronwill.nightconfig.core.conversion.Path;
import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;

/** Editable server configuration. Listener, authentication and proxy trust policy are file-only. */
public final class EditableServerSettings {

    public @NotNull General general = new General();
    public @NotNull ConfigSettings.Notifications notifications = new ConfigSettings.Notifications();
    public @NotNull Discord discord = new Discord();
    public @NotNull Ntfy ntfy = new Ntfy();
    @Path("resource_history")
    public @NotNull ConfigSettings.ResourceHistory resourceHistory = new ConfigSettings.ResourceHistory();
    @Path("crafting_history")
    public @NotNull ConfigSettings.CraftingHistory craftingHistory = new ConfigSettings.CraftingHistory();
    @Path("resource_tracking")
    public @NotNull ConfigSettings.ResourceTracking resourceTracking = new ConfigSettings.ResourceTracking();

    /** Read-only view: shared sections belong to a published configuration and must not be mutated. */
    static @NotNull EditableServerSettings fromConfig(@NotNull ConfigSettings config) {
        EditableServerSettings settings = new EditableServerSettings();
        settings.general.checkForUpdates = config.general.checkForUpdates;
        settings.general.defaultTheme = config.general.defaultTheme;
        settings.notifications = config.notifications;
        settings.discord.roleId = config.discord.roleId;
        settings.ntfy.host = config.ntfy.host;
        settings.ntfy.topic = config.ntfy.topic;
        settings.ntfy.user = config.ntfy.user;
        settings.resourceHistory = config.resourceHistory;
        settings.craftingHistory = config.craftingHistory;
        settings.resourceTracking = config.resourceTracking;
        return settings;
    }

    /** Omitted values preserve existing secrets; empty strings explicitly clear them. Never returned in responses. */
    @Desugar
    public record SecretUpdates(@OptionalInput @Nullable String discordWebhook,
        @OptionalInput @Nullable String ntfyPassword) {}

    public static final class Discord {

        @Path("role_id")
        public @NotNull String roleId = "";
    }

    public static final class Ntfy {

        public @NotNull String host = "";
        public @NotNull String topic = "";
        public @NotNull String user = "";
    }

    public static final class General {

        @Path("check_for_updates")
        public boolean checkForUpdates;
        @Path("default_theme")
        public @NotNull String defaultTheme = "";
    }
}
