package pl.kuba6000.ae2webintegration.ae2interface;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;

/**
 * Snapshot-based {@link IConfigValue} implementation for Forge 1.7.10's
 * synchronous {@code Configuration} API.
 * <p>
 * Forge reads config values at definition time (unlike NeoForge's lazy
 * {@code ConfigValue<T>}), so this class stores the value returned by the
 * {@code configuration.getXxx(...)} call. On reload a new
 * {@code ForgeConfigValue} is created with the re-read value.
 *
 * @param <T> the value type (Integer, String, Boolean)
 */
public class ForgeConfigValue<T> implements IConfigValue<T> {

    private final T value;

    ForgeConfigValue(T value) {
        this.value = value;
    }

    @Override
    public T get() {
        return value;
    }
}
