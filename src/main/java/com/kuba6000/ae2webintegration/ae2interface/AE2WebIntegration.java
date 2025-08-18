package com.kuba6000.ae2webintegration.ae2interface;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkConstants;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import com.kuba6000.ae2webintegration.core.api.IAEWebInterface;

@Mod(value = AE2WebIntegration.MODID)
@Mod.EventBusSubscriber(modid = AE2WebIntegration.MODID)
public class AE2WebIntegration {

    public static final String MODID = "ae2webintegration_interface";
    public static final Logger LOG = LogManager.getLogger(MODID);

    public AE2WebIntegration() {
        ModLoadingContext.get()
            .registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
        // SecurityCache.registerOpPlayer(
        // IAEWebInterface.getInstance()
        // .getAEWebGameProfile());
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    private static class eventHandler {

        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            // This is where you can do common setup tasks
            IAEWebInterface.getInstance()
                .initAEInterface(AE.instance);
        }
    }

}
