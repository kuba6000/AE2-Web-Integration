package pl.kuba6000.ae2webintegration.ae2interface.proxy;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

import pl.kuba6000.ae2webintegration.Tags;
import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.ae2interface.FMLEventHandler;
import pl.kuba6000.ae2webintegration.ae2interface.commands.BaseCommandHandler;
import pl.kuba6000.ae2webintegration.ae2interface.commands.CommandBuilder;
import pl.kuba6000.ae2webintegration.ae2interface.config.Config;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.ae2interface.platform.Platform;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.commands.CommandBootstrap;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Config.init(event.getModConfigurationDirectory());
        Config.synchronizeConfiguration();
        CoreEngine.init(new Platform(event.getModConfigurationDirectory()), Tags.VERSION, "-forge-1.12.2");

        AE2WebIntegration.LOG.info("AE2WebIntegration loading at version " + CoreEngine.getModVersion());

        FMLEventHandler eventHandler = new FMLEventHandler();
        FMLCommonHandler.instance()
            .bus()
            .register(eventHandler);
        MinecraftForge.EVENT_BUS.register(eventHandler);
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        CommandBuilder builder = new CommandBuilder();
        CommandBootstrap.init(builder);
        event.registerServerCommand(new BaseCommandHandler(builder.getRootNodes()));
    }

    public void serverStarted(FMLServerStartedEvent event) {
        CoreEngine.onServerStarted();
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        AE.getInstance()
            .clearPlayerSources(null);
        CoreEngine.onServerStopping();
    }

    public void serverStopped(FMLServerStoppedEvent event) {
        CoreEngine.onServerStopped();
    }
}
