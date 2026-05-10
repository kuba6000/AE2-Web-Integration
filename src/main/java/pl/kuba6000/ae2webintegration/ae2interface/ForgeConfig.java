package pl.kuba6000.ae2webintegration.ae2interface;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import pl.kuba6000.ae2webintegration.core.api.ConfigKey;
import pl.kuba6000.ae2webintegration.core.api.IConfigProvider;

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

    public static void synchronizeConfiguration(IConfigProvider provider) {
        Configuration configuration = new Configuration(configFile);
        provider.setValue(
            ConfigKey.AE_PORT,
            configuration.getInt(
                ConfigKey.AE_PORT.getKey(),
                ConfigKey.AE_PORT.getCategory(),
                (int) ConfigKey.AE_PORT.getDefaultValue(),
                1,
                65535,
                ConfigKey.AE_PORT.getDescription()));
        provider.setValue(
            ConfigKey.AE_PASSWORD,
            configuration.getString(
                ConfigKey.AE_PASSWORD.getKey(),
                ConfigKey.AE_PASSWORD.getCategory(),
                (String) ConfigKey.AE_PASSWORD.getDefaultValue(),
                ConfigKey.AE_PASSWORD.getDescription()));
        provider.setValue(
            ConfigKey.ALLOW_NO_PASSWORD_ON_LOCALHOST,
            configuration.getBoolean(
                ConfigKey.ALLOW_NO_PASSWORD_ON_LOCALHOST.getKey(),
                ConfigKey.ALLOW_NO_PASSWORD_ON_LOCALHOST.getCategory(),
                (boolean) ConfigKey.ALLOW_NO_PASSWORD_ON_LOCALHOST.getDefaultValue(),
                ConfigKey.ALLOW_NO_PASSWORD_ON_LOCALHOST.getDescription()));
        provider.setValue(
            ConfigKey.AE_PUBLIC_MODE,
            configuration.getBoolean(
                ConfigKey.AE_PUBLIC_MODE.getKey(),
                ConfigKey.AE_PUBLIC_MODE.getCategory(),
                (boolean) ConfigKey.AE_PUBLIC_MODE.getDefaultValue(),
                ConfigKey.AE_PUBLIC_MODE.getDescription()));
        provider.setValue(
            ConfigKey.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE,
            configuration.getInt(
                ConfigKey.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE.getKey(),
                ConfigKey.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE.getCategory(),
                (int) ConfigKey.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE.getDefaultValue(),
                1,
                999999999,
                ConfigKey.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE.getDescription()));

        provider.setValue(
            ConfigKey.DISCORD_WEBHOOK,
            configuration.getString(
                ConfigKey.DISCORD_WEBHOOK.getKey(),
                ConfigKey.DISCORD_WEBHOOK.getCategory(),
                (String) ConfigKey.DISCORD_WEBHOOK.getDefaultValue(),
                ConfigKey.DISCORD_WEBHOOK.getDescription()));
        provider.setValue(
            ConfigKey.DISCORD_ROLE_ID,
            configuration.getString(
                ConfigKey.DISCORD_ROLE_ID.getKey(),
                ConfigKey.DISCORD_ROLE_ID.getCategory(),
                (String) ConfigKey.DISCORD_ROLE_ID.getDefaultValue(),
                ConfigKey.DISCORD_ROLE_ID.getDescription()));

        provider.setValue(
            ConfigKey.TRACKING_TRACK_MACHINE_CRAFTING,
            configuration.getBoolean(
                ConfigKey.TRACKING_TRACK_MACHINE_CRAFTING.getKey(),
                ConfigKey.TRACKING_TRACK_MACHINE_CRAFTING.getCategory(),
                (boolean) ConfigKey.TRACKING_TRACK_MACHINE_CRAFTING.getDefaultValue(),
                ConfigKey.TRACKING_TRACK_MACHINE_CRAFTING.getDescription()));

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
