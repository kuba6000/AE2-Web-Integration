package com.kuba6000.ae2webintegration;

import com.kuba6000.ae2webintegration.commands.ReloadCommandHandler;
import com.kuba6000.ae2webintegration.utils.VersionChecker;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.init(event.getSuggestedConfigurationFile());
        Config.synchronizeConfiguration();

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
        event.registerServerCommand(new ReloadCommandHandler());
    }

    public void serverStarted(FMLServerStartedEvent event) {
        AE2Controller.init();
    }
}
