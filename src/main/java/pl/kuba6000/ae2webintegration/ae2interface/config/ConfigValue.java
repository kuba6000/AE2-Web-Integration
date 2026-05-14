package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;

/**
 * Wraps a NeoForge {@link ModConfigSpec.ConfigValue} inside the
 * platform-agnostic {@link IConfigValue} interface.
 * <p>
 * Until {@code ModConfigEvent.Loading} fires, {@code ConfigValue.get()}
 * throws {@link IllegalStateException}. This wrapper catches that and returns
 * the hardcoded default, allowing code that runs <em>before</em> the config
 * event (e.g. static initializers triggered by {@code WebEngine.init()}) to
 * read configuration values without crashing.
 * <p>
 * After the loading event, every call to {@link #get()} reads from the live
 * NeoForge config system — no explicit reload/apply step is required.
 *
 * @param <T> the value type (Integer, String, Boolean)
 */
public class ConfigValue<T> implements IConfigValue<T> {

    private final ModConfigSpec.ConfigValue<T> configValue;
    private final T defaultValue;

    public ConfigValue(ModConfigSpec.ConfigValue<T> configValue, T defaultValue) {
        this.configValue = configValue;
        this.defaultValue = defaultValue;
    }

    @Override
    public T get() {
        try {
            return configValue.get();
        } catch (IllegalStateException e) {
            // Config not yet loaded — return the hardcoded default.
            return defaultValue;
        }
    }
}
