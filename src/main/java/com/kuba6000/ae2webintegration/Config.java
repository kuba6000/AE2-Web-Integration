package com.kuba6000.ae2webintegration;

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

    // discord
    public static String DISCORD_WEBHOOK = "";
    public static String DISCORD_ROLE_ID = "";

    public static void synchronizeConfiguration() {
        Configuration configuration = new Configuration(configFile);
        AE_PORT = configuration
            .getInt("port", Configuration.CATEGORY_GENERAL, AE_PORT, 1, 65535, "Port for the hosted website");
        AE_PASSWORD = configuration
            .getString("password", Configuration.CATEGORY_GENERAL, AE_PASSWORD, "Password for the hosted website");

        DISCORD_WEBHOOK = configuration
            .getString("discord_webhook", "discord", "", "Discord webhook url (OPTIONAL, leave empty to ignore)");
        DISCORD_ROLE_ID = configuration
            .getString("discord_role_id", "discord", "", "Role to ping on message (OPTIONAL, leave empty to ignore)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static void init(File configFile) {
        Config.configFile = configFile;
    }
}
