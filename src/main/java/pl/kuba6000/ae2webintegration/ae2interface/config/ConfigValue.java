package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.minecraftforge.common.ForgeConfigSpec;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;

/**
 * Wraps a Forge {@link ForgeConfigSpec.ConfigValue} inside the
 * platform-agnostic {@link IConfigValue} interface.
 * <p>
 * Until {@code ModConfigEvent.Loading} fires, {@code ConfigValue.get()}
 * throws {@link IllegalStateException}. This wrapper catches that and returns
 * the hardcoded default, allowing code that runs <em>before</em> the config
 * event to read configuration values without crashing.
 *
 * @param <T> the value type (Integer, String, Boolean)
 */
public class ConfigValue<T> implements IConfigValue<T> {

    private final ForgeConfigSpec.ConfigValue<T> configValue;
    private final T defaultValue;

    public ConfigValue(ForgeConfigSpec.ConfigValue<T> configValue, T defaultValue) {
        this.configValue = configValue;
        this.defaultValue = defaultValue;
    }

    @Override
    public T get() {
        try {
            return configValue.get();
        } catch (IllegalStateException e) {
            return defaultValue;
        }
    }
}
