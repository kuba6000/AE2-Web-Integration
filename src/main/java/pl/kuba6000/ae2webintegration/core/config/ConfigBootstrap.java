package pl.kuba6000.ae2webintegration.core.config;

import pl.kuba6000.ae2webintegration.core.PasswordHelper;
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
    public static IConfigValue<String> aePasswordValue = PasswordHelper::generateDefaultPassword;
    public static IConfigValue<Boolean> allowNoPasswordOnLocalhostValue = () -> true;

    public static IConfigValue<String> trustedProxiesValue = () -> "";
    public static IConfigValue<Boolean> aePublicModeValue = () -> true;
    public static IConfigValue<Integer> aeMaxRequestsBeforeLoggedInPerMinuteValue = () -> 20;
    public static IConfigValue<Boolean> checkForUpdatesValue = () -> true;

    // --- Discord ---

    public static IConfigValue<String> discordWebhookValue = () -> "";
    public static IConfigValue<String> discordRoleIdValue = () -> "";
    public static IConfigValue<Integer> discordMinimumCraftingDurationSecondsValue = () -> 0;
    public static IConfigValue<Integer> discordMinimumCraftingAmountValue = () -> 0;

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
        String defaultPassword = PasswordHelper.generateDefaultPassword();
        aePasswordValue = builder.defineString("password", defaultPassword, "Password for the admin account");
        allowNoPasswordOnLocalhostValue = builder.defineBoolean(
            "allow_no_password_on_localhost",
            true,
            "Don't require login using loopback address (127.0.0.1/localhost)."
                + " WARNING: behind a reverse proxy every request arrives from the proxy, which usually is"
                + " localhost, so leaving this enabled would give every visitor admin access."
                + " Set trusted_proxies as well, or turn this off.");
        trustedProxiesValue = builder.defineString(
            "trusted_proxies",
            "",
            "Extra reverse proxies whose X-Forwarded-For / X-Real-IP headers should be believed,"
                + " as comma-separated addresses/CIDRs, e.g. \"192.168.1.10, 10.0.0.0/24\"."
                + " A proxy running on this same machine is always accepted and needs no entry here."
                + " Only list proxies you control: believing those headers from anyone would let a client"
                + " claim any address it likes."
                + " Think twice before entering a whole private range - that lets any device on your"
                + " network claim to be localhost, and with allow_no_password_on_localhost enabled"
                + " that means admin.");
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

        discordWebhookValue = builder
            .defineString("discord_webhook", "", "Webhook url for discord integration, keep empty to disable");
        discordRoleIdValue = builder.defineString(
            "discord_role_id",
            "",
            "Role id to ping on errors, keep empty to disable pinging (if webhook is empty it will do nothing)");
        discordMinimumCraftingDurationSecondsValue = builder.defineInt(
            "discord_minimum_crafting_duration_seconds",
            0,
            0,
            Integer.MAX_VALUE,
            "Minimum crafting duration in seconds required for a Discord notification (0 disables this filter)");
        discordMinimumCraftingAmountValue = builder.defineInt(
            "discord_minimum_crafting_amount",
            0,
            0,
            Integer.MAX_VALUE,
            "Minimum final output amount required for a Discord notification (0 disables this filter)");

        trackingTrackMachineCraftingValue = builder.defineBoolean(
            "track_machine_crafting",
            false,
            "Track crafting jobs run directly by machines? (Not manually ordered)");
    }

}
