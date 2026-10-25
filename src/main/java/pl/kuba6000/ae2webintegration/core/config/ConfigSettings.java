package pl.kuba6000.ae2webintegration.core.config;

import com.electronwill.nightconfig.core.conversion.Path;

import pl.kuba6000.ae2webintegration.core.PasswordHelper;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Defaults and bounds belong alongside their settings.
public final class ConfigSettings {

    public General general = new General();
    public Telemetry telemetry = new Telemetry();
    public Notifications notifications = new Notifications();
    public Discord discord = new Discord();
    public Ntfy ntfy = new Ntfy();
    @Comment("Per-network history bounds and defaults. Reload applies bounds immediately; database cleanup follows in the background.")
    @Path("resource_history")
    public ResourceHistory resourceHistory = new ResourceHistory();
    @Path("crafting_history")
    @Comment("Completed crafting history bounds and defaults. Reload applies bounds immediately; database cleanup follows in the background.")
    public CraftingHistory craftingHistory = new CraftingHistory();
    @Path("resource_tracking")
    @Comment("Live observation and background capacity controls. Changes apply on the next server tick.")
    public ResourceTracking resourceTracking = new ResourceTracking();

    public static final class Telemetry {

        @Comment({
            "Send a hashed mod version, Minecraft version, public mode, singleplayer status and a per-start session ID over HTTPS.",
            "Disable here or with /ae2webintegration telemetry off. No player, world or network contents are sent." })
        public boolean enabled = true;
    }

    public static final class General {

        /** HTTP port, from 1 to 65535. */
        @Comment("Web server port (1-65535).")
        @WebSetting(WebSetting.Access.FILE_ONLY)
        public int port = 2324;

        /**
         * Allow administrators, including trusted localhost clients, to read and edit server settings through the API.
         */
        @Path("allow_server_settings_editing")
        @Comment("Allow administrators and trusted localhost users to view and edit server settings through the web panel.")
        @WebSetting(WebSetting.Access.FILE_ONLY)
        public boolean allowServerSettingsEditing = true;

        /** Admin account password. Never returned by the API. */
        @Comment("Password for the Admin web account. Keep this secret.")
        @WebSetting(WebSetting.Access.FILE_ONLY)
        public String password = PasswordHelper.generateDefaultPassword();

        /** Grant administrator access to loopback clients without a password. */
        @Path("allow_no_password_on_localhost")
        @Comment("Allow loopback clients to access the panel as admin without a password.")
        @WebSetting(WebSetting.Access.FILE_ONLY)
        public boolean allowNoPasswordOnLocalhost = true;

        /**
         * Comma-separated proxy IP addresses or CIDRs trusted to forward client addresses. Loopback is always trusted.
         */
        @Path("trusted_proxies")
        @Comment({ "Comma-separated trusted proxy IPs or CIDRs. Loopback proxies are trusted automatically.",
            "Only trust proxies you control; their forwarded client address affects admin access." })
        @WebSetting(WebSetting.Access.FILE_ONLY)
        public String trustedProxies = "";

        /** Allow individual player accounts. When false, only Admin can log in. */
        @Path("public_mode")
        @Comment("Allow individual player accounts. When false, only the Admin account can log in.")
        @WebSetting(WebSetting.Access.FILE_ONLY)
        public boolean publicMode = true;

        /** Maximum unauthenticated requests per client per minute, from 1 to 1000. */
        @Path("max_requests_before_logged_in_per_minute")
        @Comment("Unauthenticated requests per client per minute (1-1000).")
        @WebSetting(WebSetting.Access.FILE_ONLY)
        public int maxRequestsBeforeLoggedInPerMinute = 20;

        @Path("check_for_updates")
        @Comment("Check for mod updates.")
        public boolean checkForUpdates = true;

        /**
         * Default theme: {@code builtin:default} or {@code server:<theme id>}. Unavailable themes fall back to builtin.
         */
        @Path("default_theme")
        @Comment({ "Default web theme: builtin:default or server:<theme id>.",
            "Server theme ZIPs are read from the themes directory at server startup." })
        public String defaultTheme = "builtin:default";

    }

    /** Filters for crafting notifications, which are available only when public mode is disabled. */
    public static final class Notifications {

        /** Minimum crafting duration in seconds. Zero disables this filter; both notification minimums must be met. */
        @Path("minimum_crafting_duration_seconds")
        @Comment("Minimum crafting duration in seconds for a notification. Zero disables this filter.")
        public int minimumCraftingDurationSeconds;

        /** Minimum crafted quantity. Zero disables this filter; both notification minimums must be met. */
        @Path("minimum_crafting_amount")
        @Comment("Minimum crafted amount for a notification. Both notification minimums must be met.")
        public int minimumCraftingAmount;

        /** Web interface URL opened by clicking an ntfy notification. Empty disables the link. */
        @Path("full_domain")
        @Comment("Web UI address opened when an ntfy notification is clicked. Empty disables the link.")
        public String fullDomain = "";

    }

    public static final class Discord {

        /** Discord webhook URL. Empty disables this destination. Never returned by the API. */
        @Comment("Discord webhook URL. Empty disables notifications; public mode must also be disabled.")
        @WebSetting(WebSetting.Access.SECRET)
        public String webhook = "";

        public enum CraftingStyle {
            SIMPLE,
            SMALL_ICON,
            IMAGE_CARD
        }

        /**
         * Crafting notification format: SIMPLE text, SMALL_ICON thumbnail or IMAGE_CARD card. Icons use the installed
         * pack.
         */
        @Path("crafting_style")
        @Comment("Crafting notification style: SIMPLE (text), SMALL_ICON (item thumbnail), IMAGE_CARD (dark card). Images use the installed icon pack when available.")
        public CraftingStyle craftingStyle = CraftingStyle.IMAGE_CARD;

        /** Role ID to mention in notifications. Empty disables mentions. */
        @Path("role_id")
        @Comment("Role to mention in notifications. Empty disables mentions.")
        public String roleId = "";

    }

    public static final class Ntfy {

        /** ntfy server address. HTTPS is assumed without a protocol; empty disables this destination. */
        @Comment("ntfy host. Empty disables this destination. https is assumed when no protocol is set.")
        public String host = "";

        /** Destination topic. Empty disables this destination. */
        @Comment("ntfy topic. Empty disables this destination.")
        public String topic = "";

        /** ntfy username. Username and password must either both be supplied or both be empty. */
        @Comment("ntfy username. Leave empty when the topic does not require authentication.")
        public String user = "";

        /** ntfy password. Never returned by the API. */
        @Comment("ntfy password. Leave empty when the topic does not require authentication.")
        @WebSetting(WebSetting.Access.SECRET)
        public String password = "";

    }

    /** Administrator bounds and defaults for each network's resource history. */
    public static final class ResourceHistory {

        /** Allowed seconds between saved stock samples. Independent of live terminal updates. */
        @Path("stock_sample_interval_seconds")
        @Comment("Seconds between saved quantity samples; each network selects a value within these bounds.")
        public Range stockSampleIntervalSeconds = new Range(60, 900, 300);

        /** Allowed seconds covered by each saved input/output total. Does not change the recent flow window. */
        @Path("flow_flush_interval_seconds")
        @Comment("Seconds covered by each saved input/output total; recent activity remains independent.")
        public Range flowFlushIntervalSeconds = new Range(60, 900, 60);

        /** Allowed days of resource history, subject to the record limit. */
        @Path("history_retention_days")
        @Comment("Days of detailed history selected per network, subject to the record cap below.")
        public Range historyRetentionDays = new Range(1, 15, 7);

        /**
         * Maximum history records per network. Must be positive; reaching it shortens the selected retention period.
         */
        @Path("history_max_rows_per_grid")
        @Comment({ "Maximum history records per network; the cap can shorten the selected retention.",
            "Approximate rows: days * 86400 * (active flow resources / flow seconds + changed stock resources / stock seconds), plus coverage and initial samples.",
            "Example: 100 active flow resources each minute for 7 days produce 1008000 FLOW rows before stock and coverage." })
        public int historyMaxRowsPerGrid = 250000;
    }

    /** Administrator bounds and defaults for each network's completed crafting history. */
    public static final class CraftingHistory {

        /** Allowed days of completed or cancelled crafting history, subject to the job limit. */
        @Path("history_retention_days")
        @Comment("Days of completed crafting history selected per network, subject to the job cap below.")
        public Range historyRetentionDays = new Range(1, 15, 7);

        /** Maximum completed or cancelled jobs per network. Must be positive; oldest jobs are removed in full. */
        @Path("history_max_jobs_per_grid")
        @Comment("Maximum completed or cancelled crafting jobs per network; oldest jobs are removed in full.")
        public int historyMaxJobsPerGrid = 1000;
    }

    /** Inclusive bounds for a network setting. Requires {@code 1 <= min <= defaultValue <= max}. */
    public static final class Range {

        /** Smallest allowed value, in the containing setting's units. */
        public int min;
        /** Largest allowed value, in the containing setting's units. */
        public int max;
        /** Initial value for new networks. Existing values are clamped to the bounds when the policy changes. */
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

        /** Minimum live update interval for open storage or crafting CPU views, in milliseconds. At least 50. */
        @Path("active_interval_millis")
        @Comment("Minimum observation interval of a storage or crafting CPU view while it is in use, in milliseconds.")
        public int activeIntervalMillis = 250;

        /** Seconds before the next storage observation must refresh the full state. Must be positive. */
        @Path("reconciliation_interval_seconds")
        @Comment("Maximum interval between full storage reconciliations, in seconds.")
        public int reconciliationIntervalSeconds = 300;

        /** Maximum distinct resources tracked per network. Must be positive. */
        @Path("max_resources_per_grid")
        @Comment("Maximum distinct resource identities retained by one network's active tracker.")
        public int maxResourcesPerGrid = 262144;

        /** Maximum resource and flow rows awaiting processing. Must be positive; overflow creates a history gap. */
        @Path("max_queued_rows")
        @Comment("Shared background queue capacity in resource/flow rows. Overflow reports a gap.")
        public int maxQueuedRows = 1048576;

    }

    void validate() {
        craftingHistory.historyRetentionDays.validate();
        if (craftingHistory.historyMaxJobsPerGrid < 1)
            throw new IllegalArgumentException("Crafting history job cap must be positive");
        resourceHistory.stockSampleIntervalSeconds.validate();
        resourceHistory.flowFlushIntervalSeconds.validate();
        resourceHistory.historyRetentionDays.validate();
        if (resourceHistory.historyMaxRowsPerGrid < 1)
            throw new IllegalArgumentException("History record cap must be positive");
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
