package pl.kuba6000.ae2webintegration.core;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config {

    static {
        ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
        INSTANCE = new Config(BUILDER);
        SPEC = BUILDER.build();
    }
    public static final Config INSTANCE;
    public static final ModConfigSpec SPEC;
    public static ModConfig CONFIG;

    private static Path configDirectory;

    public final ModConfigSpec.ConfigValue<String> AE_PASSWORD;
    public final ModConfigSpec.ConfigValue<Integer> AE_PORT;
    public final ModConfigSpec.ConfigValue<Boolean> ALLOW_NO_PASSWORD_ON_LOCALHOST;
    public final ModConfigSpec.ConfigValue<Boolean> AE_PUBLIC_MODE;
    public final ModConfigSpec.ConfigValue<Integer> AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE;

    // discord
    public final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK;
    public final ModConfigSpec.ConfigValue<String> DISCORD_ROLE_ID;

    // tracking
    // TODO: Add more customization options (order time, size, item type ? etc.)
    public final ModConfigSpec.ConfigValue<Boolean> TRACKING_TRACK_MACHINE_CRAFTING;

    private Config(ModConfigSpec.Builder builder) {
        builder.push("General");
        AE_PORT = builder.comment("Port for the hosted website")
            .defineInRange("port", 2324, 1, 65535);
        AE_PASSWORD = builder.comment("Password for the admin account")
            .define(
                "password",
                new Random().ints(48, 122 + 1)
                    .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                    .limit(16)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString());
        ALLOW_NO_PASSWORD_ON_LOCALHOST = builder
            .comment("Don't require to login using loopback address (127.0.0.1/localhost)")
            .define("allow_no_password_on_localhost", true);
        AE_PUBLIC_MODE = builder.comment(
            "Public server mode = enable registration system on the website, players will be able to register and login to monitor their own ae networks, "
                + "if disabled, there is only one admin account with password set in the config file with access to all networks on the server")
            .define("public_mode", true);
        AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE = builder
            .comment("How many requests can be made before user is logged in per minute")
            .defineInRange("max_requests_before_logged_in_per_minute", 20, 1, 1000);
        builder.pop();
        builder.push("Discord");
        DISCORD_WEBHOOK = builder
            .comment("Discord webhook url (OPTIONAL, leave empty to ignore) (WORKS ONLY IF PUBLIC_MODE IS DISABLED)")
            .define("webhook", "");
        DISCORD_ROLE_ID = builder.comment("Role to ping on message (OPTIONAL, leave empty to ignore)")
            .define("role_id", "");
        builder.pop();
        builder.push("Tracking");
        TRACKING_TRACK_MACHINE_CRAFTING = builder.comment("Track automated crafting jobs (not ordered by player)")
            .define("track_machine_crafting", false);
        builder.pop();

    }

    public static File getConfigFile(String fileName) {
        if (configDirectory == null) {
            configDirectory = FMLPaths.CONFIGDIR.get()
                .resolve("ae2webintegration/");
            if (Files.notExists(configDirectory)) {
                try {
                    Files.createDirectories(configDirectory);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return configDirectory.resolve(fileName)
            .toFile();
    }

    @SubscribeEvent
    private static void onReload(ModConfigEvent.Reloading event) {
        AE2Controller.stopHTTPServer();
        AE2Controller.startHTTPServer();
    }
}
