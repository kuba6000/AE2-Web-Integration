package pl.kuba6000.ae2webintegration.ae2interface.config;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.api.ConfigKey;

@EventBusSubscriber(modid = AE2WebIntegration.MODID, bus = EventBusSubscriber.Bus.MOD)
public class NeoForgeConfig {

    public static final ModConfigSpec SPEC;
    public static final NeoForgeConfig INSTANCE;

    public final ModConfigSpec.ConfigValue<Integer> AE_PORT;
    public final ModConfigSpec.ConfigValue<String> AE_PASSWORD;
    public final ModConfigSpec.ConfigValue<Boolean> ALLOW_NO_PASSWORD_ON_LOCALHOST;
    public final ModConfigSpec.ConfigValue<Boolean> AE_PUBLIC_MODE;
    public final ModConfigSpec.ConfigValue<Integer> AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE;
    public final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK;
    public final ModConfigSpec.ConfigValue<String> DISCORD_ROLE_ID;
    public final ModConfigSpec.ConfigValue<Boolean> TRACKING_TRACK_MACHINE_CRAFTING;
    public final ModConfigSpec.ConfigValue<Boolean> CHECK_FOR_UPDATES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        INSTANCE = new NeoForgeConfig(builder);
        SPEC = builder.build();
    }

    private NeoForgeConfig(ModConfigSpec.Builder builder) {
        builder.push("general");
        AE_PORT = builder.comment("Port for the hosted website")
            .defineInRange("port", (int) ConfigKey.AE_PORT.getDefaultValue(), 1, 65535);
        AE_PASSWORD = builder.comment("Password for the admin account")
            .define("password", (String) ConfigKey.AE_PASSWORD.getDefaultValue());
        ALLOW_NO_PASSWORD_ON_LOCALHOST = builder
            .comment("Don't require login using loopback address (127.0.0.1/localhost)")
            .define(
                "allow_no_password_on_localhost",
                (boolean) ConfigKey.ALLOW_NO_PASSWORD_ON_LOCALHOST.getDefaultValue());
        AE_PUBLIC_MODE = builder
            .comment(
                "If enabled every player will have their own 'account'"
                    + " (good for public servers with multiple ME Networks)")
            .define("public_mode", (boolean) ConfigKey.AE_PUBLIC_MODE.getDefaultValue());
        AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE = builder
            .comment("Max requests per minute before logging in (anti brute force)")
            .defineInRange(
                "max_requests_before_logged_in_per_minute",
                (int) ConfigKey.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE.getDefaultValue(),
                1,
                1000);
        CHECK_FOR_UPDATES = builder.comment("Check for updates")
            .define("check_for_updates", (boolean) ConfigKey.CHECK_FOR_UPDATES.getDefaultValue());
        builder.pop();

        builder.push("discord");
        DISCORD_WEBHOOK = builder.comment("Webhook url for discord integration, keep empty to disable")
            .define("discord_webhook", (String) ConfigKey.DISCORD_WEBHOOK.getDefaultValue());
        DISCORD_ROLE_ID = builder
            .comment(
                "Role id to ping on errors, keep empty to disable pinging (if webhook is empty it will do nothing)")
            .define("discord_role_id", (String) ConfigKey.DISCORD_ROLE_ID.getDefaultValue());
        builder.pop();

        builder.push("tracking");
        TRACKING_TRACK_MACHINE_CRAFTING = builder
            .comment("Track crafting jobs run directly by machines? (Not manually ordered)")
            .define("track_machine_crafting", (boolean) ConfigKey.TRACKING_TRACK_MACHINE_CRAFTING.getDefaultValue());
        builder.pop();
    }

    /** Push all config values from the NeoForge config spec into core Config via IConfigProvider. */
    public static void applyConfig() {
        applyConfig(INSTANCE);
    }

    private static void applyConfig(NeoForgeConfig config) {
        var provider = Config.getProvider();
        provider.setValue(ConfigKey.AE_PORT, config.AE_PORT.get());
        provider.setValue(ConfigKey.AE_PASSWORD, config.AE_PASSWORD.get());
        provider.setValue(ConfigKey.ALLOW_NO_PASSWORD_ON_LOCALHOST, config.ALLOW_NO_PASSWORD_ON_LOCALHOST.get());
        provider.setValue(ConfigKey.AE_PUBLIC_MODE, config.AE_PUBLIC_MODE.get());
        provider.setValue(
            ConfigKey.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE,
            config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE.get());
        provider.setValue(ConfigKey.DISCORD_WEBHOOK, config.DISCORD_WEBHOOK.get());
        provider.setValue(ConfigKey.DISCORD_ROLE_ID, config.DISCORD_ROLE_ID.get());
        provider.setValue(ConfigKey.TRACKING_TRACK_MACHINE_CRAFTING, config.TRACKING_TRACK_MACHINE_CRAFTING.get());
        provider.setValue(ConfigKey.CHECK_FOR_UPDATES, config.CHECK_FOR_UPDATES.get());
    }

    // --- Event handlers ---

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        applyConfig();
        AE2WebIntegration.LOG.info("Config loaded");
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        applyConfig();
        AE2Controller.stopHTTPServer();
        AE2Controller.startHTTPServer();
        AE2WebIntegration.LOG.info("Config reloaded, web server restarted");
    }
}
