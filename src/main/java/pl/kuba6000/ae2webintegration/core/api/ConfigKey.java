package pl.kuba6000.ae2webintegration.core.api;

public enum ConfigKey {

    // General
    AE_PORT("port", "general", 2324, "Port for the hosted website"),
    AE_PASSWORD("password", "general", "", "Password for the admin account"),
    ALLOW_NO_PASSWORD_ON_LOCALHOST(
        "allow_no_password_on_localhost",
        "general",
        true,
        "Don't require login using loopback address (127.0.0.1/localhost)"),
    AE_PUBLIC_MODE(
        "public_mode",
        "general",
        true,
        "If enabled every player will have their own 'account' (good for public servers with multiple ME Networks)"),
    AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE(
        "max_requests_before_logged_in_per_minute",
        "general",
        20,
        "Max requests per minute before logging in (anti brute force)"),

    // Discord
    DISCORD_WEBHOOK(
        "discord_webhook",
        "discord",
        "",
        "Webhook url for discord integration, keep empty to disable"),
    DISCORD_ROLE_ID(
        "discord_role_id",
        "discord",
        "",
        "Role id to ping on errors, keep empty to disable pinging (if webhook is empty it will do nothing)"),

    // Tracking
    TRACKING_TRACK_MACHINE_CRAFTING(
        "track_machine_crafting",
        "tracking",
        false,
        "Track crafting jobs run directly by machines ? (Not manually ordered)"),

    // Updates
    CHECK_FOR_UPDATES(
        "check_for_updates",
        "general",
        true,
        "Check for updates");

    private final String key;
    private final String category;
    private final Object defaultValue;
    private final String description;

    ConfigKey(String key, String category, Object defaultValue, String description) {
        this.key = key;
        this.category = category;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    public String getKey() {
        return key;
    }

    public String getCategory() {
        return category;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public String getDescription() {
        return description;
    }
}
