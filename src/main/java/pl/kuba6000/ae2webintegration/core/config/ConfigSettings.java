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
    @Comment("Per-network history bounds and defaults. Reload applies bounds immediately; database cleanup follows in the background.")
    public Data data = new Data();
    @Path("resource_tracking")
    @Comment("Live observation and background capacity controls. Changes take effect after restarting the server.")
    public ResourceTracking resourceTracking = new ResourceTracking();

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

        @Path("default_theme")
        @Comment({ "Default web theme: builtin:default or server:<theme id>.",
            "Server theme ZIPs are read from the themes directory at server startup." })
        public String defaultTheme = "builtin:default";

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

    public static final class Data {

        @Path("stock_sample_interval_seconds")
        @Comment("Seconds between saved quantity samples; each network selects a value within these bounds.")
        public Range stockSampleIntervalSeconds = new Range(60, 86400, 300);

        @Path("flow_flush_interval_seconds")
        @Comment("Seconds covered by each saved input/output total; recent activity remains independent.")
        public Range flowFlushIntervalSeconds = new Range(60, 86400, 60);

        @Path("history_retention_days")
        @Comment("Days of detailed history selected per network, subject to the record cap below.")
        public Range historyRetentionDays = new Range(1, 365, 7);

        @Path("history_max_rows_per_grid")
        @Comment({ "Maximum history records per network; the cap can shorten the selected retention.",
            "Approximate rows: days * 86400 * (active flow resources / flow seconds + changed stock resources / stock seconds), plus coverage and initial samples.",
            "Example: 100 active flow resources each minute for 7 days produce 1008000 FLOW rows before stock and coverage." })
        public int historyMaxRowsPerGrid = 250000;
    }

    public static final class Range {

        public int min;
        public int max;
        @Path("default")
        public int defaultValue;

        public Range() {}

        public Range(int min, int max, int defaultValue) {
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
        }

        public boolean contains(int value) {
            return value >= min && value <= max;
        }

        public int clamp(int value) {
            return Math.max(min, Math.min(max, value));
        }

        public void validate(int value) {
            if (!contains(value)) throw new IllegalArgumentException("Setting is outside the active server bounds");
        }

        void validate() {
            if (min < 1 || min > defaultValue || defaultValue > max)
                throw new IllegalArgumentException("Data bounds require 1 <= min <= default <= max");
        }
    }

    public static final class ResourceTracking {

        @Path("active_interval_millis")
        @Comment("Minimum observation interval of a storage or crafting CPU view while it is in use, in milliseconds.")
        public int activeIntervalMillis = 250;

        @Path("reconciliation_interval_seconds")
        @Comment("Maximum interval between full storage reconciliations, in seconds.")
        public int reconciliationIntervalSeconds = 300;

        @Path("max_resources_per_grid")
        @Comment("Maximum distinct resource identities retained by one network's active tracker.")
        public int maxResourcesPerGrid = 262144;

        @Path("max_queued_rows")
        @Comment("Shared background queue capacity in resource/flow rows. Overflow reports a gap.")
        public int maxQueuedRows = 1048576;

    }

    void validate() {
        data.stockSampleIntervalSeconds.validate();
        data.flowFlushIntervalSeconds.validate();
        data.historyRetentionDays.validate();
        if (data.historyMaxRowsPerGrid < 1) throw new IllegalArgumentException("History record cap must be positive");
        if (general.port < 1 || general.port > 65535)
            throw new IllegalArgumentException("general.port must be between 1 and 65535");
        if (general.maxRequestsBeforeLoggedInPerMinute < 1 || general.maxRequestsBeforeLoggedInPerMinute > 1000) {
            throw new IllegalArgumentException(
                "general.max_requests_before_logged_in_per_minute must be between 1 and 1000");
        }
        if (notifications.minimumCraftingDurationSeconds < 0 || notifications.minimumCraftingAmount < 0) {
            throw new IllegalArgumentException("Notification minimums must not be negative");
        }
        if (resourceTracking.activeIntervalMillis < 50 || resourceTracking.reconciliationIntervalSeconds < 1
            || resourceTracking.maxResourcesPerGrid < 1
            || resourceTracking.maxQueuedRows < 1) {
            throw new IllegalArgumentException(
                "Resource tracking limits must be positive; active interval must be at least 50 ms");
        }
    }
}
