package pl.kuba6000.ae2webintegration.core.config;

import com.electronwill.nightconfig.core.conversion.Path;

import pl.kuba6000.ae2webintegration.core.PasswordHelper;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Defaults and bounds belong alongside their settings.
public final class ConfigSettings {

    public General general = new General();
    public Notifications notifications = new Notifications();
    public Discord discord = new Discord();
    public Ntfy ntfy = new Ntfy();
    public Tracking tracking = new Tracking();

    public static final class General {

        @Comment("Web server port (1-65535).")
        public int port = 2324;

        @Comment("Password for the Admin web account. Keep this secret.")
        public String password = PasswordHelper.generateDefaultPassword();

        @Path("allow_no_password_on_localhost")
        @Comment("Allow loopback clients to access the panel as admin without a password.")
        public boolean allowNoPasswordOnLocalhost = true;

        @Path("trusted_proxies")
        @Comment({ "Comma-separated trusted proxy IPs or CIDRs. Loopback proxies are trusted automatically.",
            "Only trust proxies you control; their forwarded client address affects admin access." })
        public String trustedProxies = "";

        @Path("public_mode")
        @Comment("Allow individual player accounts. When false, only the Admin account can log in.")
        public boolean publicMode = true;

        @Path("max_requests_before_logged_in_per_minute")
        @Comment("Unauthenticated requests per client per minute (1-1000).")
        public int maxRequestsBeforeLoggedInPerMinute = 20;

        @Path("check_for_updates")
        @Comment("Check for mod updates.")
        public boolean checkForUpdates = true;

    }

    public static final class Notifications {

        @Path("minimum_crafting_duration_seconds")
        @Comment("Minimum crafting duration in seconds for a notification. Zero disables this filter.")
        public int minimumCraftingDurationSeconds;

        @Path("minimum_crafting_amount")
        @Comment("Minimum crafted amount for a notification. Both notification minimums must be met.")
        public int minimumCraftingAmount;

        @Path("full_domain")
        @Comment("Web UI address opened when an ntfy notification is clicked. Empty disables the link.")
        public String fullDomain = "";

    }

    public static final class Discord {

        @Comment("Discord webhook URL. Empty disables notifications; public mode must also be disabled.")
        public String webhook = "";

        @Path("role_id")
        @Comment("Role to mention on errors. Empty disables mentions.")
        public String roleId = "";

    }

    public static final class Ntfy {

        @Comment("ntfy host. Empty disables this destination. https is assumed when no protocol is set.")
        public String host = "";

        @Comment("ntfy topic. Empty disables this destination.")
        public String topic = "";

        @Comment("ntfy username. Leave empty when the topic does not require authentication.")
        public String user = "";

        @Comment("ntfy password. Leave empty when the topic does not require authentication.")
        public String password = "";

    }

    public static final class Tracking {

        @Path("track_machine_crafting")
        @Comment("Track crafting requested directly by machines, rather than only player requests.")
        public boolean trackMachineCrafting;

    }

    void validate() {
        if (general.port < 1 || general.port > 65535)
            throw new IllegalArgumentException("general.port must be between 1 and 65535");
        if (general.maxRequestsBeforeLoggedInPerMinute < 1 || general.maxRequestsBeforeLoggedInPerMinute > 1000) {
            throw new IllegalArgumentException(
                "general.max_requests_before_logged_in_per_minute must be between 1 and 1000");
        }
        if (notifications.minimumCraftingDurationSeconds < 0 || notifications.minimumCraftingAmount < 0) {
            throw new IllegalArgumentException("Notification minimums must not be negative");
        }
    }
}
