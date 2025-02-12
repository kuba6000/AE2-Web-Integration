package com.kuba6000.ae2webintegration.core;

import java.io.File;
import java.util.Random;

import net.minecraftforge.common.config.Configuration;

public class Config {

    private static File configFile;

    public static String AE_PASSWORD = new Random().ints(48, 122 + 1)
        .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
        .limit(16)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
    public static int AE_PORT = 2324;
    public static boolean ALLOW_NO_PASSWORD_ON_LOCALHOST = true;
    public static int AE_CPUS_THRESHOLD = 5;

    // discord
    public static String DISCORD_WEBHOOK = "";
    public static String DISCORD_ROLE_ID = "";

    // tracking
    public static boolean TRACKING_TRACK_MACHINE_CRAFTING = false;

    public static void synchronizeConfiguration() {
        Configuration configuration = new Configuration(configFile);
        AE_PORT = configuration
            .getInt("port", Configuration.CATEGORY_GENERAL, AE_PORT, 1, 65535, "Port for the hosted website");
        AE_PASSWORD = configuration
            .getString("password", Configuration.CATEGORY_GENERAL, AE_PASSWORD, "Password for the hosted website");
        ALLOW_NO_PASSWORD_ON_LOCALHOST = configuration.getBoolean(
            "allow_no_password_on_localhost",
            Configuration.CATEGORY_GENERAL,
            ALLOW_NO_PASSWORD_ON_LOCALHOST,
            "Don't require password using loopback address (127.0.0.1/localhost)");
        AE_CPUS_THRESHOLD = configuration.getInt(
            "cpu_count_threshold",
            Configuration.CATEGORY_GENERAL,
            AE_CPUS_THRESHOLD,
            1,
            100,
            "How many crafting units should be considered enough to detect main network?");

        DISCORD_WEBHOOK = configuration
            .getString("discord_webhook", "discord", "", "Discord webhook url (OPTIONAL, leave empty to ignore)");
        DISCORD_ROLE_ID = configuration
            .getString("discord_role_id", "discord", "", "Role to ping on message (OPTIONAL, leave empty to ignore)");

        TRACKING_TRACK_MACHINE_CRAFTING = configuration.getBoolean(
            "track_machine_crafting",
            "tracking",
            TRACKING_TRACK_MACHINE_CRAFTING,
            "Track automated crafting jobs (not ordered by player)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static void init(File configFile) {
        Config.configFile = configFile;
    }
}
