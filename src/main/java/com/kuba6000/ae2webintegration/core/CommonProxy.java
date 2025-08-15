package com.kuba6000.ae2webintegration.core;

import com.kuba6000.ae2webintegration.Tags;
import com.kuba6000.ae2webintegration.core.commands.BaseCommandHandler;
import com.kuba6000.ae2webintegration.core.discord.DiscordManager;
import com.kuba6000.ae2webintegration.core.utils.VersionChecker;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.init(event.getModConfigurationDirectory());
        Config.synchronizeConfiguration();
        WebData.loadData();
        GridData.loadData();

        AE2WebIntegration.LOG.info("AE2WebIntegration loading at version " + Tags.VERSION);
        if (VersionChecker.isOutdated()) AE2WebIntegration.LOG.warn(
            "You are not on latest version ! Consider updating to {} at https://github.com/kuba6000/AE2-Web-Integration/releases/latest",
            VersionChecker.getLatestTag());

        FMLCommonHandler.instance()
            .bus()
            .register(new FMLEventHandler());
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {

    }

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new BaseCommandHandler());
    }

    public void serverStarted(FMLServerStartedEvent event) {
        AE2Controller.init();
        DiscordManager.init();
        if (!Config.AE_PUBLIC_MODE && !Config.DISCORD_WEBHOOK.isEmpty()) {
            DiscordManager.postMessageNonBlocking(
                new DiscordManager.DiscordEmbed("AE2 Web Integration", "Discord integration started!"));
        } else if (Config.AE_PUBLIC_MODE && !Config.DISCORD_WEBHOOK.isEmpty()) {
            DiscordManager.postMessageNonBlocking(
                new DiscordManager.DiscordEmbed(
                    "AE2 Web Integration",
                    "Warning!\nDiscord integration webhook is set in the config, but the public mode is enabled!\nDiscord integration will be disabled!",
                    15548997));
        }
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        AE2Controller.stopHTTPServer();
    }

}
