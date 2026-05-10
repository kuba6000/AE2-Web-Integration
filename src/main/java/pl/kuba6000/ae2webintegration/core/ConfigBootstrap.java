package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.api.IConfigBuilder;
import pl.kuba6000.ae2webintegration.core.api.IConfigValue;

/**
 * Defines ALL configuration keys for AE2 Web Integration.
 *
 * Called once during mod initialization with a platform-specific
 * {@link IConfigBuilder} implementation. Before {@link #init(IConfigBuilder)}
 * has been called every value returns its hard-coded default.
 */
public class ConfigBootstrap {

    // --- General ---

    public static IConfigValue<Integer> aePortValue = () -> 2324;
    public static IConfigValue<String> aePasswordValue = () -> "";
    public static IConfigValue<Boolean> allowNoPasswordOnLocalhostValue = () -> true;
    public static IConfigValue<Boolean> aePublicModeValue = () -> true;
    public static IConfigValue<Integer> aeMaxRequestsBeforeLoggedInPerMinuteValue = () -> 20;
    public static IConfigValue<Boolean> checkForUpdatesValue = () -> true;

    // --- Discord ---

    public static IConfigValue<String> discordWebhookValue = () -> "";
    public static IConfigValue<String> discordRoleIdValue = () -> "";

    // --- Tracking ---

    public static IConfigValue<Boolean> trackingTrackMachineCraftingValue = () -> false;

    private ConfigBootstrap() {}

    /**
     * Initializes all config values through the given builder.
     * After this call, all {@code *Value} fields are backed by the
     * platform-specific config system instead of static defaults.
     */
    public static void init(IConfigBuilder builder) {
        aePortValue = builder.defineInt("port", 2324, 1, 65535, "Port for the hosted website");
        aePasswordValue = builder.defineString("password", "", "Password for the admin account");
        allowNoPasswordOnLocalhostValue = builder.defineBoolean(
            "allow_no_password_on_localhost",
            true,
            "Don't require login using loopback address (127.0.0.1/localhost)");
        aePublicModeValue = builder.defineBoolean(
            "public_mode",
            true,
            "If enabled every player will have their own 'account'"
                + " (good for public servers with multiple ME Networks)");
        aeMaxRequestsBeforeLoggedInPerMinuteValue = builder.defineInt(
            "max_requests_before_logged_in_per_minute",
            20,
            1,
            1000,
            "Max requests per minute before logging in (anti brute force)");
        checkForUpdatesValue = builder.defineBoolean("check_for_updates", true, "Check for updates");

        discordWebhookValue = builder.defineString(
            "discord_webhook",
            "",
            "Webhook url for discord integration, keep empty to disable");
        discordRoleIdValue = builder.defineString(
            "discord_role_id",
            "",
            "Role id to ping on errors, keep empty to disable pinging (if webhook is empty it will do nothing)");

        trackingTrackMachineCraftingValue = builder.defineBoolean(
            "track_machine_crafting",
            false,
            "Track crafting jobs run directly by machines? (Not manually ordered)");
    }
}
