package pl.kuba6000.ae2webintegration.ae2interface;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkConstants;

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
@Mod.EventBusSubscriber(modid = AE2WebIntegration.MODID)
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
        ModLoadingContext.get()
            .registerConfig(ModConfig.Type.COMMON, Config.SPEC, "ae2webintegration/ae2webintegration.toml");
        ModLoadingContext.get()
            .registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(
                    () -> NetworkConstants.IGNORESERVERONLY,
                    (remote, isServer) -> true));

        CoreEngine.init(platform, version, "-forge-1.20.1");
        LOG.info("AE2WebIntegration loading at version {}", version);
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
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
