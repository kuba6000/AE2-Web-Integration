package pl.kuba6000.ae2webintegration.ae2interface;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.api.IAEWebInterface;

@Mod(value = AE2WebIntegration.MODID)
public class AE2WebIntegration {

    public static final String MODID = "ae2webintegration_interface";
    public static final Logger LOG = LogManager.getLogger(MODID);

    public AE2WebIntegration() {
        // ModLoadingContext.get()
        // .registerExtensionPoint(
        // IExtensionPoint.DisplayTest.class,
        // () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
        // SecurityCache.registerOpPlayer(
        // IAEWebInterface.getInstance()
        // .getAEWebGameProfile());
    }

    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD)
    private static class eventHandler {

        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            // This is where you can do common setup tasks
            IAEWebInterface.getInstance()
                .initAEInterface(AE.instance);
        }
    }

}
