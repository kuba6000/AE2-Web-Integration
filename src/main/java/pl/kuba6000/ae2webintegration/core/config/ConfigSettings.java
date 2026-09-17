package pl.kuba6000.ae2webintegration.core.config;

import com.electronwill.nightconfig.core.conversion.Path;

import pl.kuba6000.ae2webintegration.core.PasswordHelper;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Defaults and bounds belong alongside their settings.
final class ConfigSettings {

    General general = new General();
    Discord discord = new Discord();
    Tracking tracking = new Tracking();

    static final class General {

        @Comment("Web server port (1-65535).")
        int port = 2324;
        @Comment("Password for the Admin web account. Keep this secret.")
        String password = PasswordHelper.generateDefaultPassword();
        @Path("allow_no_password_on_localhost")
        @Comment("Allow loopback clients to access the panel as admin without a password.")
        boolean allowNoPasswordOnLocalhost = true;
        @Path("trusted_proxies")
        @Comment({ "Comma-separated trusted proxy IPs or CIDRs. Loopback proxies are trusted automatically.",
            "Only trust proxies you control; their forwarded client address affects admin access." })
        String trustedProxies = "";
        @Path("public_mode")
        @Comment("Allow individual player accounts. When false, only the Admin account can log in.")
        boolean publicMode = true;
        @Path("max_requests_before_logged_in_per_minute")
        @Comment("Unauthenticated requests per client per minute (1-1000).")
        int maxRequestsBeforeLoggedInPerMinute = 20;
        @Path("check_for_updates")
        @Comment("Check for mod updates.")
        boolean checkForUpdates = true;
    }

    static final class Discord {

        @Comment("Discord webhook URL. Empty disables notifications; public mode must also be disabled.")
        String webhook = "";
        @Path("role_id")
        @Comment("Role to mention on errors. Empty disables mentions.")
        String roleId = "";
        @Path("minimum_crafting_duration_seconds")
        @Comment("Minimum crafting duration in seconds for a notification. Zero disables this filter.")
        int minimumCraftingDurationSeconds;
        @Path("minimum_crafting_amount")
        @Comment("Minimum crafted amount for a notification. Both notification minimums must be met.")
        int minimumCraftingAmount;
    }

    static final class Tracking {

        @Path("track_machine_crafting")
        @Comment("Track crafting requested directly by machines, rather than only player requests.")
        boolean trackMachineCrafting;
    }

    void validate() {
        if (general.port < 1 || general.port > 65535)
            throw new IllegalArgumentException("general.port must be between 1 and 65535");
        if (general.maxRequestsBeforeLoggedInPerMinute < 1 || general.maxRequestsBeforeLoggedInPerMinute > 1000) {
            throw new IllegalArgumentException(
                "general.max_requests_before_logged_in_per_minute must be between 1 and 1000");
        }
        if (discord.minimumCraftingDurationSeconds < 0 || discord.minimumCraftingAmount < 0) {
            throw new IllegalArgumentException("Discord notification minimums must not be negative");
        }
    }
}
