package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.util.Random;

import net.minecraftforge.common.config.Configuration;

public class Config {

    private static File configDirectory;
    private static File configFile;

    public static String AE_PASSWORD = new Random().ints(48, 122 + 1)
        .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
        .limit(16)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
    public static int AE_PORT = 2324;
    public static boolean ALLOW_NO_PASSWORD_ON_LOCALHOST = true;
    public static boolean AE_PUBLIC_MODE = true;
    public static int AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE = 20;

    // discord
    public static String DISCORD_WEBHOOK = "";
    public static String DISCORD_ROLE_ID = "";

    // tracking
    // TODO: Add more customization options (order time, size, item type ? etc.)
    public static boolean TRACKING_TRACK_MACHINE_CRAFTING = false;

    public static void synchronizeConfiguration() {
        Configuration configuration = new Configuration(configFile);
        AE_PORT = configuration
            .getInt("port", Configuration.CATEGORY_GENERAL, AE_PORT, 1, 65535, "Port for the hosted website");
        AE_PASSWORD = configuration
            .getString("password", Configuration.CATEGORY_GENERAL, AE_PASSWORD, "Password for the admin account");
        ALLOW_NO_PASSWORD_ON_LOCALHOST = configuration.getBoolean(
            "allow_no_password_on_localhost",
            Configuration.CATEGORY_GENERAL,
            ALLOW_NO_PASSWORD_ON_LOCALHOST,
            "Don't require to login using loopback address (127.0.0.1/localhost)");
        AE_PUBLIC_MODE = configuration.getBoolean(
            "public_mode",
            Configuration.CATEGORY_GENERAL,
            AE_PUBLIC_MODE,
            "Public server mode = enable registration system on the website, players will be able to register and login to monitor their own ae networks, "
                + "if disabled, there is only one admin account with password set in the config file with access to all networks on the server");
        AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE = configuration.getInt(
            "max_requests_before_logged_in_per_minute",
            Configuration.CATEGORY_GENERAL,
            AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE,
            1,
            1000,
            "How many requests can be made before user is logged in per minute");

        DISCORD_WEBHOOK = configuration.getString(
            "discord_webhook",
            "discord",
            "",
            "Discord webhook url (OPTIONAL, leave empty to ignore) (WORKS ONLY IF PUBLIC_MODE IS DISABLED)");
        DISCORD_ROLE_ID = configuration
            .getString("discord_role_id", "discord", "", "Role to ping on message (OPTIONAL, leave empty to ignore)");

        TRACKING_TRACK_MACHINE_CRAFTING = configuration.getBoolean(
            "track_machine_crafting",
            "tracking",
            TRACKING_TRACK_MACHINE_CRAFTING,
            "Track automated crafting jobs (not ordered by player)");

        if (configuration.hasKey(Configuration.CATEGORY_GENERAL, "cpu_count_threshold")) {
            configuration.getInt(
                "cpu_count_threshold",
                Configuration.CATEGORY_GENERAL,
                0,
                0,
                0,
                "[DEPRECATED] This option is no longer used, you can remove it from your config file.");
        }

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static void init(File configDirectory) {
        Config.configDirectory = new File(configDirectory, "ae2webintegration");
        Config.configFile = new File(Config.configDirectory, "ae2webintegration.cfg");
        if (!Config.configDirectory.exists()) {
            Config.configDirectory.mkdirs();
            File oldConfigFile = new File(configDirectory, "ae2webintegration.cfg");
            if (oldConfigFile.exists()) {
                oldConfigFile.renameTo(Config.configFile);
            }
        }

    }

    public static File getConfigFile(String fileName) {
        return new File(configDirectory, fileName);
    }
}
