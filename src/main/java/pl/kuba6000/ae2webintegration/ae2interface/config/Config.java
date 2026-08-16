package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;

import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;

/**
 * Forge 1.20.1 config wiring. This class does NOT define what config keys
 * exist — that is owned by {@link ConfigBootstrap}. Instead it:
 * <ol>
 * <li>Creates a {@link ForgeConfigSpec.Builder}</li>
 * <li>Wraps it in a {@link ConfigBuilder}</li>
 * <li>Passes the wrapper to {@link ConfigBootstrap#init} so core defines all keys</li>
 * <li>Builds the {@link ForgeConfigSpec} and exposes it as {@link #SPEC}</li>
 * </ol>
 *
 * Because {@link ConfigValue} reads live from the Forge config system on
 * every {@code get()}, no explicit value-copying step is needed — values are
 * always current after Forge fires its config events.
 */
@Mod.EventBusSubscriber(modid = AE2WebIntegration.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    public static final ForgeConfigSpec SPEC;
    private static volatile ModConfig loadedConfig;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
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

    /** Re-reads only this mod's Forge config, matching the behavior before the core integration. */
    public static void reloadFromDisk() {
        ModConfig config = loadedConfig;
        if (config == null) {
            throw new IllegalStateException("AE2 Web Integration config has not been loaded yet");
        }
        if (!(config.getConfigData() instanceof CommentedFileConfig)) {
            throw new IllegalStateException("AE2 Web Integration config is not backed by a file");
        }

        ((CommentedFileConfig) config.getConfigData()).load();
        SPEC.afterReload();
    }
}
