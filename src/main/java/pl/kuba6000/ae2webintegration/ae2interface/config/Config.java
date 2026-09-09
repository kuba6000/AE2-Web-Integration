package pl.kuba6000.ae2webintegration.ae2interface.config;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import net.minecraftforge.common.config.Configuration;

import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;

/**
 * Forge 1.12.2 config wiring. This class does NOT define what config keys
 * exist — that is owned by {@link ConfigBootstrap}. Instead, it:
 * <ol>
 * <li>Sets up the config directory via {@link #init(File)}</li>
 * <li>Creates a {@link Configuration} from the config file</li>
 * <li>Wraps it in a {@link ConfigBuilder}</li>
 * <li>Passes the wrapper to {@link ConfigBootstrap#init} so core defines all keys</li>
 * <li>Saves the config if any keys were modified</li>
 * </ol>
 *
 * Because Forge reads values synchronously at definition time, each call to
 * {@link #synchronizeConfiguration()} creates fresh {@link ConfigValue}
 * snapshots holding the current on-disk values.
 */
public class Config {

    private static File configFile;

    public static void init(File rootConfigDirectory) {
        File configDirectory = new File(rootConfigDirectory, "ae2webintegration");
        configFile = new File(configDirectory, "ae2webintegration.cfg");
        try {
            Files.createDirectories(configDirectory.toPath());
            File oldConfigFile = new File(rootConfigDirectory, "ae2webintegration.cfg");
            if (!configFile.exists() && oldConfigFile.exists()) {
                Files.move(oldConfigFile.toPath(), configFile.toPath());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare configuration file " + configFile, e);
        }
    }

    public static void synchronizeConfiguration() {
        Configuration configuration = new Configuration(configFile);
        ConfigBootstrap.init(new ConfigBuilder(configuration));
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
