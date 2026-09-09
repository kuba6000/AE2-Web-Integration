package pl.kuba6000.ae2webintegration.core.api;

/**
 * Platform-agnostic config builder. Interface layer provides a real implementation
 * wrapping NeoForge/Forge ModConfigSpec.Builder (or equivalent).
 * <p>
 * Core uses this solely inside {@code ConfigBootstrap.init(IConfigBuilder)} to
 * define all config keys and obtain their {@link IConfigValue} handles.
 */
public interface IConfigBuilder {

    /** Define an integer config property with range validation. */
    IConfigValue<Integer> defineInt(String key, int defaultValue, int min, int max, String comment);

    /** Define a string config property. */
    IConfigValue<String> defineString(String key, String defaultValue, String comment);

    /** Define a boolean config property. */
    IConfigValue<Boolean> defineBoolean(String key, boolean defaultValue, String comment);
}
