package pl.kuba6000.ae2webintegration.ae2interface.config;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;

/**
 * Snapshot-based {@link IConfigValue} implementation for Forge 1.12.2's
 * synchronous {@code Configuration} API.
 * <p>
 * Forge reads config values at definition time (unlike NeoForge's lazy
 * {@code ConfigValue<T>}), so this class stores the value returned by the
 * {@code configuration.getXxx(...)} call. On reload a new
 * {@code ConfigValue} is created with the re-read value.
 *
 * @param <T> the value type (Integer, String, Boolean)
 */
public class ConfigValue<T> implements IConfigValue<T> {

    private final T value;

    ConfigValue(T value) {
        this.value = value;
    }

    @Override
    public T get() {
        return value;
    }
}
