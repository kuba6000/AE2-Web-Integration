package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.minecraftforge.common.ForgeConfigSpec;

import pl.kuba6000.ae2webintegration.core.api.IConfigBuilder;
import pl.kuba6000.ae2webintegration.core.api.IConfigValue;

/**
 * {@link IConfigBuilder} implementation wrapping Forge 1.20.1's
 * {@link ForgeConfigSpec.Builder}.
 */
public class ConfigBuilder implements IConfigBuilder {

    private final ForgeConfigSpec.Builder builder;

    public ConfigBuilder(ForgeConfigSpec.Builder builder) {
        this.builder = builder;
    }

    @Override
    public IConfigValue<Integer> defineInt(String key, int defaultValue, int min, int max, String comment) {
        return new ConfigValue<>(
            builder.comment(comment)
                .defineInRange(key, defaultValue, min, max),
            defaultValue);
    }

    @Override
    public IConfigValue<String> defineString(String key, String defaultValue, String comment) {
        return new ConfigValue<>(
            builder.comment(comment)
                .define(key, defaultValue),
            defaultValue);
    }

    @Override
    public IConfigValue<Boolean> defineBoolean(String key, boolean defaultValue, String comment) {
        return new ConfigValue<>(
            builder.comment(comment)
                .define(key, defaultValue),
            defaultValue);
    }
}
