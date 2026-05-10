package pl.kuba6000.ae2webintegration.core.api;

/**
 * Represents a single configuration value, backed by the platform-specific config system.
 *
 * @param <T> the value type (Integer, String, Boolean)
 */
public interface IConfigValue<T> {

    /** Returns the current configuration value. */
    T get();
}
