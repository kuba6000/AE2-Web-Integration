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
 * <li>Returns a {@link NeoForgeConfigValue} wrapping the resulting
 * {@link ModConfigSpec.ConfigValue}</li>
 * </ol>
 *
 * All config keys are defined at the root level (no {@code push}/{@code pop}
 * categories). Categories can be added later if needed by extending the
 * {@link IConfigBuilder} contract.
 */
public class NeoForgeConfigBuilder implements IConfigBuilder {

    private final ModConfigSpec.Builder builder;

    public NeoForgeConfigBuilder(ModConfigSpec.Builder builder) {
        this.builder = builder;
    }

    @Override
    public IConfigValue<Integer> defineInt(String key, int defaultValue, int min, int max, String comment) {
        return new NeoForgeConfigValue<>(
            builder.comment(comment)
                .defineInRange(key, defaultValue, min, max));
    }

    @Override
    public IConfigValue<String> defineString(String key, String defaultValue, String comment) {
        return new NeoForgeConfigValue<>(
            builder.comment(comment)
                .define(key, defaultValue));
    }

    @Override
    public IConfigValue<Boolean> defineBoolean(String key, boolean defaultValue, String comment) {
        return new NeoForgeConfigValue<>(
            builder.comment(comment)
                .define(key, defaultValue));
    }
}
