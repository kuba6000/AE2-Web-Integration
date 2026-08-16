package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.minecraftforge.common.config.Configuration;

import pl.kuba6000.ae2webintegration.core.api.IConfigBuilder;
import pl.kuba6000.ae2webintegration.core.api.IConfigValue;

/**
 * {@link IConfigBuilder} implementation wrapping Forge 1.12.2's
 * {@link Configuration} API.
 * <p>
 * Unlike NeoForge's lazy {@code ConfigValue<T>}, Forge reads config values
 * synchronously at definition time. Each {@code defineXxx} call invokes
 * {@code configuration.getXxx(...)} immediately and returns a snapshot-based
 * {@link ConfigValue} holding the result.
 * <p>
 * Config keys are mapped to the same categories ("general", "discord",
 * "tracking") that the old {@code ConfigKey} enum used, preserving the
 * existing config file structure across upgrades.
 */
public class ConfigBuilder implements IConfigBuilder {

    private final Configuration configuration;

    public ConfigBuilder(Configuration configuration) {
        this.configuration = configuration;
    }

    private static String category(String key) {
        if (key.startsWith("discord_")) return "discord";
        if ("track_machine_crafting".equals(key)) return "tracking";
        return "general";
    }

    @Override
    public IConfigValue<Integer> defineInt(String key, int defaultValue, int min, int max, String comment) {
        return new ConfigValue<>(configuration.getInt(key, category(key), defaultValue, min, max, comment));
    }

    @Override
    public IConfigValue<String> defineString(String key, String defaultValue, String comment) {
        return new ConfigValue<>(configuration.getString(key, category(key), defaultValue, comment));
    }

    @Override
    public IConfigValue<Boolean> defineBoolean(String key, boolean defaultValue, String comment) {
        return new ConfigValue<>(configuration.getBoolean(key, category(key), defaultValue, comment));
    }
}
