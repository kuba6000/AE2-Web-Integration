package pl.kuba6000.ae2webintegration.core.config;

import java.io.File;

public class Config {

    // todo: migrate to JSON format

    private static File configDirectory;

    // --- Delegating accessors (backed by ConfigBootstrap) ---

    // General
    public static int AE_PORT() {
        return ConfigBootstrap.aePortValue.get();
    }

    public static String AE_PASSWORD() {
        return ConfigBootstrap.aePasswordValue.get();
    }

    public static String TRUSTED_PROXIES() {
        return ConfigBootstrap.trustedProxiesValue.get();
    }

    public static boolean ALLOW_NO_PASSWORD_ON_LOCALHOST() {
        return ConfigBootstrap.allowNoPasswordOnLocalhostValue.get();
    }

    public static boolean AE_PUBLIC_MODE() {
        return ConfigBootstrap.aePublicModeValue.get();
    }

    public static int AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE() {
        return ConfigBootstrap.aeMaxRequestsBeforeLoggedInPerMinuteValue.get();
    }

    public static boolean CHECK_FOR_UPDATES() {
        return ConfigBootstrap.checkForUpdatesValue.get();
    }

    // Discord
    public static String DISCORD_WEBHOOK() {
        return ConfigBootstrap.discordWebhookValue.get();
    }

    public static String DISCORD_ROLE_ID() {
        return ConfigBootstrap.discordRoleIdValue.get();
    }

    // Tracking
    public static boolean TRACKING_TRACK_MACHINE_CRAFTING() {
        return ConfigBootstrap.trackingTrackMachineCraftingValue.get();
    }

    // --- Directory / file setup ---

    public static void init(File configDirectory) {
        Config.configDirectory = new File(configDirectory, "ae2webintegration");
        if (!Config.configDirectory.exists()) {
            Config.configDirectory.mkdirs();
        }
    }

    public static File getConfigDirectory() {
        return configDirectory;
    }

    public static File getConfigFile(String fileName) {
        return new File(configDirectory, fileName);
    }
}
