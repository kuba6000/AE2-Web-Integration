package pl.kuba6000.ae2webintegration.core;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.Tags;
import pl.kuba6000.ae2webintegration.core.commands.BaseCommandHandler;
import pl.kuba6000.ae2webintegration.core.discord.DiscordManager;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

@Mod(value = MODID)
@EventBusSubscriber(modid = MODID)
public class AE2WebIntegration {

    public static final String MODID = "ae2webintegration_core";
    public static final Logger LOG = LogManager.getLogger(MODID);

    public static ModContainer myContainer;

    public AE2WebIntegration() {
        // ModLoadingContext.get()
        // .registerExtensionPoint(
        // IExtensionPoint.DisplayTest.class,
        // () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
        Tags.VERSION = ModLoadingContext.get()
            .getActiveContainer()
            .getModInfo()
            .getVersion()
            .toString();
        // ModLoadingContext.get()
        // .registerConfig(ModConfig.Type.COMMON, Config.SPEC, "ae2webintegration/ae2webintegration.toml");
        myContainer = ModLoadingContext.get()
            .getActiveContainer();
        // ModLoadingContext.get()
        // .getActiveContainer()
        // .registerConfig(
        // Config.CONFIG = new ModConfig(
        // ModConfig.Type.COMMON,
        // Config.SPEC,
        // myContainer,
        // "ae2webintegration/ae2webintegration.toml"));
        Config.CONFIG = ConfigTracker.INSTANCE.registerConfig(
            ModConfig.Type.COMMON,
            Config.SPEC,
            myContainer,
            "ae2webintegration/ae2webintegration.toml");
        WebData.loadData();
        GridData.loadData();

        AE2WebIntegration.LOG.info("AE2WebIntegration loading at version {}", Tags.VERSION);
        if (VersionChecker.isOutdated()) AE2WebIntegration.LOG.warn(
            "You are not on latest version ! Consider updating to {} at https://github.com/kuba6000/AE2-Web-Integration/releases/latest",
            VersionChecker.getLatestTag());
    }

    @SubscribeEvent
    public static void commandsRegister(RegisterCommandsEvent event) {
        BaseCommandHandler.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        AE2Controller.init();
        DiscordManager.init();
        if (!Config.INSTANCE.AE_PUBLIC_MODE.get() && !Config.INSTANCE.DISCORD_WEBHOOK.get()
            .isEmpty()) {
            DiscordManager.postMessageNonBlocking(
                new DiscordManager.DiscordEmbed("AE2 Web Integration", "Discord integration started!"));
        } else if (Config.INSTANCE.AE_PUBLIC_MODE.get() && !Config.INSTANCE.DISCORD_WEBHOOK.get()
            .isEmpty()) {
                DiscordManager.postMessageNonBlocking(
                    new DiscordManager.DiscordEmbed(
                        "AE2 Web Integration",
                        "Warning!\nDiscord integration webhook is set in the config, but the public mode is enabled!\nDiscord integration will be disabled!",
                        15548997));
            }
    }

    @SubscribeEvent
    public static void serverStopping(ServerStoppingEvent event) {
        AE2Controller.stopHTTPServer();
    }

}
