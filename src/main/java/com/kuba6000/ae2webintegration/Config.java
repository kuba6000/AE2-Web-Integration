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

    public static void synchronizeConfiguration() {
        Configuration configuration = new Configuration(configFile);
        AE_PORT = configuration
            .getInt("port", Configuration.CATEGORY_GENERAL, AE_PORT, 1, 65535, "Port for the hosted website");
        AE_PASSWORD = configuration
            .getString("password", Configuration.CATEGORY_GENERAL, AE_PASSWORD, "Password for the hosted website");
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static void init(File configFile) {
        Config.configFile = configFile;
    }
}
