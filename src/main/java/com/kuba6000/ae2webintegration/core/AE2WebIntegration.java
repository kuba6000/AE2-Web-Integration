package com.kuba6000.ae2webintegration.core;

import static com.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.network.NetworkConstants;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.kuba6000.ae2webintegration.Tags;
import com.kuba6000.ae2webintegration.core.commands.BaseCommandHandler;
import com.kuba6000.ae2webintegration.core.discord.DiscordManager;
import com.kuba6000.ae2webintegration.core.utils.VersionChecker;

@Mod(value = MODID)
@Mod.EventBusSubscriber(modid = MODID)
public class AE2WebIntegration {

    public static final String MODID = "ae2webintegration_core";
    public static final Logger LOG = LogManager.getLogger(MODID);

    public AE2WebIntegration() {
        ModLoadingContext.get()
            .registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
        Tags.VERSION = ModLoadingContext.get()
            .getActiveContainer()
            .getModInfo()
            .getVersion()
            .toString();
        ModLoadingContext.get()
            .registerConfig(ModConfig.Type.COMMON, Config.SPEC, "ae2webintegration/ae2webintegration.toml");
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
