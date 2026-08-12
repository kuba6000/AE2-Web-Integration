package pl.kuba6000.ae2webintegration.ae2interface.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.Function;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.core.ConfigBootstrap;

/**
 * NeoForge config wiring. This class does NOT define what config keys exist —
 * that is owned by {@link ConfigBootstrap}. Instead it:
 * <ol>
 * <li>Creates a {@link ModConfigSpec.Builder}</li>
 * <li>Wraps it in a {@link ConfigBuilder}</li>
 * <li>Passes the wrapper to {@link ConfigBootstrap#init} so core defines all keys</li>
 * <li>Builds the {@link ModConfigSpec} and exposes it as {@link #SPEC}</li>
 * </ol>
 *
 * Because {@link ConfigValue} reads live from the NeoForge config
 * system on every {@code get()}, no explicit value-copying step is needed —
 * values are always current after NeoForge fires its config events.
 */
@EventBusSubscriber(modid = AE2WebIntegration.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config {

    public static final ModConfigSpec SPEC;
    private static volatile ModConfig loadedConfig;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ConfigBootstrap.init(new ConfigBuilder(builder));
        SPEC = builder.build();
    }

    private Config() {}

    // --- Event handlers ---

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig()
            .getSpec() != SPEC) {
            return;
        }
        loadedConfig = event.getConfig();
        AE2WebIntegration.LOG.info("Config loaded");
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig()
            .getSpec() != SPEC) {
            return;
        }
        loadedConfig = event.getConfig();
        AE2WebIntegration.LOG.info("Config reloaded");
    }

    /**
     * Forces NeoForge to reload only this mod's config. FML 4.0.41 has no public single-config reload API,
     * so this preserves the pre-integration fallback without reopening every registered COMMON config.
     */
    public static void reloadFromDisk() {
        ModConfig config = loadedConfig;
        if (config == null) {
            throw new IllegalStateException("AE2 Web Integration config has not been loaded yet");
        }

        try {
            Method loadConfig = ConfigTracker.class
                .getDeclaredMethod("loadConfig", ModConfig.class, Path.class, Function.class);
            loadConfig.setAccessible(true);
            loadConfig.invoke(
                null,
                config,
                config.getFullPath(),
                (Function<ModConfig, ModConfigEvent>) ModConfigEvent.Reloading::new);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("NeoForge failed to reload the config", cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NeoForge single-config reload API is unavailable", e);
        }
    }
}
