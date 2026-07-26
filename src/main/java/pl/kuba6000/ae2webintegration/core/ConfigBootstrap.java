package pl.kuba6000.ae2webintegration.core;

import java.security.SecureRandom;

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
    public static IConfigValue<String> aePasswordValue = () -> generateDefaultPassword();
    public static IConfigValue<Boolean> allowNoPasswordOnLocalhostValue = () -> true;

    public static IConfigValue<String> trustedProxiesValue = () -> "";
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
        String defaultPassword = generateDefaultPassword();
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

        trackingTrackMachineCraftingValue = builder.defineBoolean(
            "track_machine_crafting",
            false,
            "Track crafting jobs run directly by machines? (Not manually ordered)");
    }

    /**
     * Generates a random 16-character alphanumeric password as the config
     * default. When no password is set in the config file this default is
     * persisted, preventing the "empty password → any password accepted"
     * vulnerability.
     * <p>
     * Uses {@link SecureRandom} for the same reason session tokens do: this value ends up in the config
     * file as the admin password, and java.util.Random derives its 48-bit seed from the clock.
     */
    private static String generateDefaultPassword() {
        return new SecureRandom().ints(48, 122 + 1)
            .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
            .limit(16)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }
}
