package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
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

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ConfigBootstrap.init(new ConfigBuilder(builder));
        SPEC = builder.build();
    }

    private Config() {}

    // --- Event handlers ---

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        AE2WebIntegration.LOG.info("Config loaded");
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        AE2Controller.stopHTTPServer();
        AE2Controller.startHTTPServer();
        AE2WebIntegration.LOG.info("Config reloaded, web server restarted");
    }
}
