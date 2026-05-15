package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.ConfigBootstrap;

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

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
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
