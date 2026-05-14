package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import pl.kuba6000.ae2webintegration.core.api.IConfigBuilder;
import pl.kuba6000.ae2webintegration.core.api.IConfigValue;

/**
 * {@link IConfigBuilder} implementation that wraps NeoForge's
 * {@link ModConfigSpec.Builder}.
 * <p>
 * Each {@code defineXxx} call:
 * <ol>
 * <li>Sets the human-readable comment on the NeoForge builder</li>
 * <li>Delegates to the appropriate {@code define} / {@code defineInRange} method</li>
 * <li>Returns a {@link ConfigValue} wrapping the resulting
 * {@link ModConfigSpec.ConfigValue} together with the hardcoded default
 * (used as fallback before {@code ModConfigEvent.Loading} fires)</li>
 * </ol>
 *
 * All config keys are defined at the root level (no {@code push}/{@code pop}
 * categories). Categories can be added later if needed by extending the
 * {@link IConfigBuilder} contract.
 */
public class ConfigBuilder implements IConfigBuilder {

    private final ModConfigSpec.Builder builder;

    public ConfigBuilder(ModConfigSpec.Builder builder) {
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
