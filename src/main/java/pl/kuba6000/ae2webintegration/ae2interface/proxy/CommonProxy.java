package pl.kuba6000.ae2webintegration.ae2interface.proxy;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

import pl.kuba6000.ae2webintegration.Tags;
import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.ae2interface.FMLEventHandler;
import pl.kuba6000.ae2webintegration.ae2interface.commands.BaseCommandHandler;
import pl.kuba6000.ae2webintegration.ae2interface.commands.CommandBuilder;
import pl.kuba6000.ae2webintegration.ae2interface.config.Config;
import pl.kuba6000.ae2webintegration.ae2interface.platform.Platform;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CommandBootstrap;
import pl.kuba6000.ae2webintegration.core.GridData;
import pl.kuba6000.ae2webintegration.core.StartupHandler;
import pl.kuba6000.ae2webintegration.core.WebData;
import pl.kuba6000.ae2webintegration.core.WebEngine;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        VersionChecker.setVersionIdentifier("-forge-1.12.2");
        Config.init(event.getModConfigurationDirectory());
        Config.synchronizeConfiguration();
        WebEngine.init(new Platform(event.getModConfigurationDirectory()), Tags.VERSION);
        WebData.loadData();
        GridData.loadData();

        AE2WebIntegration.LOG.info("AE2WebIntegration loading at version " + WebEngine.getModVersion());
        StartupHandler.logOutdatedWarning();

        FMLCommonHandler.instance()
            .bus()
            .register(new FMLEventHandler());
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        CommandBuilder builder = new CommandBuilder();
        CommandBootstrap.init(builder);
        event.registerServerCommand(new BaseCommandHandler(builder.getRootNodes()));
    }

    public void serverStarted(FMLServerStartedEvent event) {
        AE2Controller.init();
        StartupHandler.handleDiscordIntegration();
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        AE2Controller.stopHTTPServer();
    }
}
