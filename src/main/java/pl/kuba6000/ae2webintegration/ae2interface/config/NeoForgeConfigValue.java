package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;

/**
 * Wraps a NeoForge {@link ModConfigSpec.ConfigValue} inside the
 * platform-agnostic {@link IConfigValue} interface.
 * <p>
 * Every call to {@link #get()} reads from the live NeoForge config system,
 * so no explicit reload/apply step is required — values update automatically
 * when NeoForge fires its config events.
 *
 * @param <T> the value type (Integer, String, Boolean)
 */
public class NeoForgeConfigValue<T> implements IConfigValue<T> {

    private final ModConfigSpec.ConfigValue<T> configValue;

    public NeoForgeConfigValue(ModConfigSpec.ConfigValue<T> configValue) {
        this.configValue = configValue;
    }

    @Override
    public T get() {
        return configValue.get();
    }
}
