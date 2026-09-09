package pl.kuba6000.ae2webintegration.ae2interface;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.ae2interface.commands.CommandBuilder;
import pl.kuba6000.ae2webintegration.ae2interface.config.Config;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.ae2interface.platform.Platform;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.IAEWebInterface;
import pl.kuba6000.ae2webintegration.core.commands.CommandBootstrap;

@Mod(value = AE2WebIntegration.MODID)
@EventBusSubscriber(modid = AE2WebIntegration.MODID)
public class AE2WebIntegration {

    public static final String MODID = "ae2webintegration";
    public static final Logger LOG = LogManager.getLogger(MODID);

    public AE2WebIntegration() {
        Platform platform = new Platform();
        String version = ModLoadingContext.get()
            .getActiveContainer()
            .getModInfo()
            .getVersion()
            .toString();

        // Register config before anything that depends on it
        ModContainer container = ModLoadingContext.get()
            .getActiveContainer();
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "ae2webintegration/ae2webintegration.toml");

        CoreEngine.init(platform, version, "-neoforge-1.21.1");
        LOG.info("AE2WebIntegration loading at version {}", version);
    }

    @EventBusSubscriber(modid = MODID)
    private static class ModEventHandler {

        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            IAEWebInterface.getInstance()
                .initAEInterface(AE.instance);
        }
    }

    @SubscribeEvent
    public static void commandsRegister(RegisterCommandsEvent event) {
        CommandBootstrap.init(new CommandBuilder(event.getDispatcher()));
    }

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        CoreEngine.onServerStarted();
    }

    @SubscribeEvent
    public static void serverStopping(ServerStoppingEvent event) {
        CoreEngine.onServerStopping();
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        CoreEngine.onServerStopped();
    }
}
