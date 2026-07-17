package pl.kuba6000.ae2webintegration.ae2interface.proxy;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import pl.kuba6000.ae2webintegration.Tags;
import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.ae2interface.FMLEventHandler;
import pl.kuba6000.ae2webintegration.ae2interface.commands.BaseCommandHandler;
import pl.kuba6000.ae2webintegration.ae2interface.commands.CommandBuilder;
import pl.kuba6000.ae2webintegration.ae2interface.config.Config;
import pl.kuba6000.ae2webintegration.ae2interface.platform.Platform;
import pl.kuba6000.ae2webintegration.core.CommandBootstrap;
import pl.kuba6000.ae2webintegration.core.WebEngine;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.init(event.getModConfigurationDirectory());
        Config.synchronizeConfiguration();
        WebEngine.init(new Platform(event.getModConfigurationDirectory()), Tags.VERSION, "-forge-1.7.10");

        AE2WebIntegration.LOG.info("AE2WebIntegration loading at version " + WebEngine.getModVersion());

        FMLCommonHandler.instance()
            .bus()
            .register(new FMLEventHandler());
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {}

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        CommandBuilder builder = new CommandBuilder();
        CommandBootstrap.init(builder);
        event.registerServerCommand(new BaseCommandHandler(builder.getRootNodes()));
    }

    public void serverStarted(FMLServerStartedEvent event) {
        WebEngine.onServerStarted();
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        WebEngine.onServerStopping();
    }
}
