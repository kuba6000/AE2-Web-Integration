package pl.kuba6000.ae2webintegration.ae2interface;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import pl.kuba6000.ae2webintegration.core.Config;

public class ForgeConfig {

    private static File configDirectory;
    private static File configFile;

    public static void init(File rootConfigDirectory) {
        configDirectory = new File(rootConfigDirectory, "ae2webintegration");
        configFile = new File(configDirectory, "ae2webintegration.cfg");
        if (!configDirectory.exists()) {
            configDirectory.mkdirs();
            File oldConfigFile = new File(rootConfigDirectory, "ae2webintegration.cfg");
            if (oldConfigFile.exists()) {
                oldConfigFile.renameTo(configFile);
            }
        }
    }

    public static void synchronizeConfiguration() {
        Configuration configuration = new Configuration(configFile);
        Config.AE_PORT = configuration
            .getInt("port", Configuration.CATEGORY_GENERAL, Config.AE_PORT, 1, 65535, "Port for the hosted website");
        Config.AE_PASSWORD = configuration.getString(
            "password",
            Configuration.CATEGORY_GENERAL,
            Config.AE_PASSWORD,
            "Password for the admin account");
        Config.ALLOW_NO_PASSWORD_ON_LOCALHOST = configuration.getBoolean(
            "allow_no_password_on_localhost",
            Configuration.CATEGORY_GENERAL,
            Config.ALLOW_NO_PASSWORD_ON_LOCALHOST,
            "Don't require to login using loopback address (127.0.0.1/localhost)");
        Config.AE_PUBLIC_MODE = configuration.getBoolean(
            "public_mode",
            Configuration.CATEGORY_GENERAL,
            Config.AE_PUBLIC_MODE,
            "If enabled every player will have their own 'account' (good for public servers with multiple ME Networks)");
        Config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE = configuration.getInt(
            "max_requests_before_logged_in_per_minute",
            Configuration.CATEGORY_GENERAL,
            Config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE,
            1,
            999999999,
            "Max requests per minute before logging in (anti brute force)");

        Config.DISCORD_WEBHOOK = configuration.getString(
            "discord_webhook",
            "discord",
            Config.DISCORD_WEBHOOK,
            "Webhook url for discord integration, keep empty to disable");
        Config.DISCORD_ROLE_ID = configuration.getString(
            "discord_role_id",
            "discord",
            Config.DISCORD_ROLE_ID,
            "Role id to ping on errors, keep empty to disable pinging (if webhook is empty it will do nothing)");

        Config.TRACKING_TRACK_MACHINE_CRAFTING = configuration.getBoolean(
            "track_machine_crafting",
            "tracking",
            Config.TRACKING_TRACK_MACHINE_CRAFTING,
            "Track crafting jobs run directly by machines ? (Not manually ordered)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
